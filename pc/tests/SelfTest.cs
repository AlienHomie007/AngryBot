using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace AngryBot
{
    /// <summary>
    /// SelfTest.exe         file format checks only
    /// SelfTest.exe --live  also records and replays real mouse drags on a test window
    ///                      (takes over the mouse for about 3 seconds - don't touch it)
    /// </summary>
    static class SelfTest
    {
        static int fails;

        static void Check(bool ok, string what)
        {
            Console.WriteLine((ok ? "PASS  " : "FAIL  ") + what);
            if (!ok) fails++;
        }

        [STAThread]
        static int Main(string[] args)
        {
            Startup.Init();
            RecordingRoundTrip();
            if (Array.IndexOf(args, "--live") >= 0) LiveRecordAndReplay();
            Console.WriteLine(fails == 0 ? "ALL PASSED" : fails + " FAILED");
            return fails == 0 ? 0 : 1;
        }

        static void RecordingRoundTrip()
        {
            List<Stroke> a = new List<Stroke>();
            Stroke s = new Stroke();
            s.GapMs = 1000;
            s.T.Add(0); s.X.Add(0.25f); s.Y.Add(0.5f);
            s.T.Add(180); s.X.Add(0.1234f); s.Y.Add(0.75f);
            a.Add(s);
            Stroke tap = new Stroke();
            tap.GapMs = 2345;
            tap.T.Add(0); tap.X.Add(0.9f); tap.Y.Add(0.1f);
            tap.T.Add(60); tap.X.Add(0.9f); tap.Y.Add(0.1f);
            a.Add(tap);

            string path = Path.Combine(Path.GetTempPath(), "angrybot_selftest.txt");
            Recording.Save(path, a, new Size(1600, 900));
            Size size;
            List<Stroke> b = Recording.Load(path, out size);
            File.Delete(path);

            Check(size == new Size(1600, 900), "recording keeps the window size");
            Check(b.Count == 2 && b[0].Count == 2 && b[1].Count == 2, "recording keeps strokes and points");
            Check(b[1].GapMs == 2345 && b[0].T[1] == 180, "recording keeps timing");
            Check(Math.Abs(b[0].X[1] - 0.1234f) < 1e-5 && Math.Abs(b[1].Y[0] - 0.1f) < 1e-5, "recording keeps positions");
        }

        // ---------------------------------------------------------------- live test

        /// <summary>Stands in for the game: logs left-button presses, drags and releases in client pixels.</summary>
        sealed class Target : Form
        {
            public readonly List<string> Log = new List<string>();
            public readonly List<Point> Downs = new List<Point>(), Ups = new List<Point>();
            public int Moves;

            public Target()
            {
                Text = "AngryBot test target";
                FormBorderStyle = FormBorderStyle.FixedSingle;
                StartPosition = FormStartPosition.Manual;
                Location = new Point(420, 160);
                ClientSize = new Size(800, 500);
                TopMost = true;
                BackColor = Color.FromArgb(120, 180, 230);
                MouseDown += (s, e) => { if (e.Button == MouseButtons.Left) Downs.Add(e.Location); };
                MouseMove += (s, e) => { if (e.Button == MouseButtons.Left) Moves++; };
                MouseUp += (s, e) => { if (e.Button == MouseButtons.Left) Ups.Add(e.Location); };
            }
        }

        /// <summary>Like Mouse, but without the AngryBot tag, so the hook treats it as the user's own hand.</summary>
        static void UserDrag(Point from, Point to, int ms)
        {
            const uint MOVE = 0x1, LEFTDOWN = 0x2, LEFTUP = 0x4, VIRTUALDESK = 0x4000, ABSOLUTE = 0x8000;
            Action<uint, Point> send = (button, p) =>
            {
                int vx = Native.GetSystemMetrics(76), vy = Native.GetSystemMetrics(77);
                int vw = Native.GetSystemMetrics(78), vh = Native.GetSystemMetrics(79);
                Native.INPUT input = new Native.INPUT();
                input.mi.dx = (int)Math.Round((p.X - vx) * 65535.0 / Math.Max(1, vw - 1));
                input.mi.dy = (int)Math.Round((p.Y - vy) * 65535.0 / Math.Max(1, vh - 1));
                input.mi.dwFlags = button | MOVE | ABSOLUTE | VIRTUALDESK;
                Native.SendInput(1, new Native.INPUT[] { input }, Marshal.SizeOf(typeof(Native.INPUT)));
            };
            send(LEFTDOWN, from);
            int steps = Math.Max(1, ms / 10);
            for (int i = 1; i <= steps; i++)
            {
                Thread.Sleep(10);
                send(0, new Point(from.X + (to.X - from.X) * i / steps, from.Y + (to.Y - from.Y) * i / steps));
            }
            send(LEFTUP, to);
        }

        static void Pump(int ms)
        {
            Stopwatch clock = Stopwatch.StartNew();
            while (clock.ElapsedMilliseconds < ms)
            {
                Application.DoEvents();
                Thread.Sleep(5);
            }
        }

        static void RunWhilePumping(ThreadStart work)
        {
            Thread t = new Thread(work);
            t.IsBackground = true;
            t.Start();
            while (t.IsAlive)
            {
                Application.DoEvents();
                Thread.Sleep(5);
            }
        }

        static void Call(object o, string method, params object[] args)
        {
            o.GetType().GetMethod(method, BindingFlags.Instance | BindingFlags.NonPublic).Invoke(o, args);
        }

        static bool Near(Point a, Point b) { return Math.Abs(a.X - b.X) <= 2 && Math.Abs(a.Y - b.Y) <= 2; }

        static void LiveRecordAndReplay()
        {
            Point cursor = Cursor.Position;
            string file = Recording.DefaultPath, backup = file + ".selftest-backup";
            if (File.Exists(file)) File.Copy(file, backup, true);
            Target target = new Target();
            RecorderBar bar = new RecorderBar();
            try
            {
                target.Show();
                bar.Show();
                Pump(300);
                Game.Use(target.Handle);
                Rectangle r = Game.ClientRect(target.Handle);
                Check(r.Size == new Size(800, 500), "finds the test window's client area (" + r + ")");

                // 1. record two "user" moves: a slingshot-style drag, then a tap
                Call(bar, "StartRecording");
                RunWhilePumping(() =>
                {
                    UserDrag(new Point(r.Left + 150, r.Top + 300), new Point(r.Left + 60, r.Top + 380), 300);
                    Thread.Sleep(400);
                    UserDrag(new Point(r.Left + 600, r.Top + 120), new Point(r.Left + 600, r.Top + 120), 50);
                });
                Pump(200);
                Call(bar, "StopAll");
                Size size;
                List<Stroke> rec = Recording.Load(file, out size);
                Check(rec.Count == 2, "recorded 2 moves (got " + rec.Count + ")");
                Check(size == r.Size, "recorded the window size");
                Check(target.Downs.Count == 2 && target.Ups.Count == 2, "the window got the user's moves while recording");
                if (rec.Count == 2)
                {
                    Check(rec[0].Count > 10, "drag kept its path (" + rec[0].Count + " points)");
                    Check(rec[0].T[rec[0].Count - 1] >= 250, "drag kept its duration (" + rec[0].T[rec[0].Count - 1] + " ms)");
                    Check(rec[1].GapMs >= 350 && rec[1].GapMs <= 700, "gap between moves kept (" + rec[1].GapMs + " ms)");
                }

                // 2. move the window, then replay: positions must follow the window
                target.Location = new Point(target.Left - 120, target.Top + 40);
                Pump(200);
                target.Downs.Clear(); target.Ups.Clear(); target.Moves = 0;
                Stopwatch clock = Stopwatch.StartNew();
                Call(bar, "StartPlaying", false);
                while (clock.ElapsedMilliseconds < 6000 && target.Ups.Count < 2) Pump(20);
                long took = clock.ElapsedMilliseconds;
                Pump(200);

                Check(target.Downs.Count == 2 && target.Ups.Count == 2, "replay pressed and released twice (" + target.Downs.Count + "/" + target.Ups.Count + ")");
                if (target.Downs.Count == 2 && target.Ups.Count == 2)
                {
                    Check(Near(target.Downs[0], new Point(150, 300)), "drag starts in the right place " + target.Downs[0]);
                    Check(Near(target.Ups[0], new Point(60, 380)), "drag ends in the right place " + target.Ups[0]);
                    Check(Near(target.Downs[1], new Point(600, 120)) && Near(target.Ups[1], new Point(600, 120)), "tap in the right place " + target.Downs[1]);
                }
                Check(target.Moves > 10, "replayed the drag path (" + target.Moves + " moves)");
                Check(took >= 1600 && took <= 2600, "replay timing ≈ 1s wait + drag + gap + tap (" + took + " ms)");
            }
            finally
            {
                Call(bar, "StopAll");
                bar.Close();
                target.Close();
                Cursor.Position = cursor;
                if (File.Exists(backup)) { File.Copy(backup, file, true); File.Delete(backup); }
                else if (File.Exists(file)) File.Delete(file);
            }
        }
    }
}
