using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace AngryBot
{
    /// <summary>One mouse drag: wait GapMs after the previous one ended, then press, follow the points, release.</summary>
    sealed class Stroke
    {
        public long GapMs;
        public readonly List<int> T = new List<int>();       // ms after the press
        public readonly List<float> X = new List<float>();   // 0..1 across the game's client area
        public readonly List<float> Y = new List<float>();   // 0..1 down the game's client area

        public int Count { get { return T.Count; } }
    }

    /// <summary>
    /// The saved recording, a text file in %APPDATA%\AngryBot:
    ///   size W H                         game client size it was recorded at
    ///   stroke GAP t:x:y t:x:y ...       one line per move
    /// </summary>
    static class Recording
    {
        public static string DefaultPath { get { return Path.Combine(Storage.Folder, "level11.txt"); } }

        public static void Save(string path, List<Stroke> strokes, Size size)
        {
            StringBuilder sb = new StringBuilder();
            sb.AppendLine("# AngryBot recording v1");
            sb.AppendLine("size " + size.Width + " " + size.Height);
            foreach (Stroke s in strokes)
            {
                sb.Append("stroke ").Append(s.GapMs);
                for (int i = 0; i < s.Count; i++)
                    sb.Append(' ').Append(s.T[i]).Append(':').Append(Storage.F(s.X[i])).Append(':').Append(Storage.F(s.Y[i]));
                sb.AppendLine();
            }
            string tmp = path + ".tmp";
            File.WriteAllText(tmp, sb.ToString());
            if (File.Exists(path)) File.Delete(path);
            File.Move(tmp, path);
        }

        public static List<Stroke> Load(string path, out Size size)
        {
            List<Stroke> strokes = new List<Stroke>();
            size = Size.Empty;
            if (!File.Exists(path)) return strokes;
            foreach (string line in File.ReadAllLines(path))
            {
                string[] parts = line.Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length == 3 && parts[0] == "size")
                {
                    size = new Size(int.Parse(parts[1]), int.Parse(parts[2]));
                }
                else if (parts.Length >= 3 && parts[0] == "stroke")
                {
                    Stroke s = new Stroke();
                    s.GapMs = long.Parse(parts[1]);
                    for (int i = 2; i < parts.Length; i++)
                    {
                        string[] p = parts[i].Split(':');
                        s.T.Add(int.Parse(p[0]));
                        s.X.Add(Storage.ParseF(p[1]));
                        s.Y.Add(Storage.ParseF(p[2]));
                    }
                    strokes.Add(s);
                }
            }
            return strokes;
        }
    }

    /// <summary>
    /// Floating control bar over Google Play Games.
    /// Rec  – you play normally; every left-button drag/click on the game is saved.
    /// Play – replays the saved moves with the same timing.  Loop – replays until Stop.
    /// </summary>
    sealed class RecorderBar : Bar
    {
        const long FIRST_GAP_MS = 1000;   // wait before the first move on Play
        const long LOOP_PAUSE_MS = 3000;  // wait between loop runs
        const float MIN_STEP_PX = 2f;     // drop drag points closer than this

        readonly MouseHook hook;
        List<Stroke> strokes;
        Size recordedSize;

        // recording (all on the UI thread, inside the hook)
        bool recording;
        Stroke current;
        Rectangle currentRect;
        uint downTime, lastUpTime;
        PointF lastPoint;

        // playback (worker thread)
        volatile bool playing;

        public RecorderBar() : base("AngryBot", Color.FromArgb(0x1B, 0x24, 0x40), Color.FromArgb(0x26, 0x33, 0x5A))
        {
            FlowLayoutPanel row = AddRow();
            AddButton(row, "● Rec", (s, e) => StartRecording());
            AddButton(row, "▶ Play", (s, e) => StartPlaying(false));
            AddButton(row, "⟳ Loop", (s, e) => StartPlaying(true));
            row = AddRow();
            AddButton(row, "■ Stop", (s, e) => StopAll());
            AddButton(row, "↶ Undo", (s, e) => Undo());
            AddButton(row, "⌖ Pick", (s, e) => { StopAll(); StartPick(); });

            try { strokes = Recording.Load(Recording.DefaultPath, out recordedSize); }
            catch (Exception) { strokes = new List<Stroke>(); }
            hook = new MouseHook(OnMouse);

            IntPtr g = Game.Find();
            if (g != IntPtr.Zero) Game.Use(g);
            SetStatus("Ready · " + strokes.Count + " moves" + (g == IntPtr.Zero ? " · open Angry Birds 2" : ""));
        }

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            playing = false;
            hook.Dispose();
            base.OnFormClosed(e);
        }

        protected override void OnStopKey() { StopAll(); }

        // ---------------------------------------------------------------- mouse hook (UI thread)

        bool OnMouse(int msg, int x, int y, uint time, bool ours)
        {
            if (HandlePick(msg, x, y, ours)) return true;
            if (UserTookOver(msg, x, y, ours) && playing)
            {
                playing = false;
                SetStatus("Stopped: you used the mouse");
            }
            if (recording && !ours) Record(msg, x, y, time);
            return false;   // the game always gets the user's own input
        }

        // ---------------------------------------------------------------- recording

        void StartRecording()
        {
            StopAll();
            if (EnsureGame() == IntPtr.Zero) return;
            strokes.Clear();
            current = null;
            recording = true;
            Save();
            SetStatus("REC · 0 moves · play the level, then ■ Stop (or F8)");
        }

        void Record(int msg, int x, int y, uint time)
        {
            if (msg == MouseHook.DOWN)
            {
                IntPtr g = Game.Current;
                Rectangle r = Game.ClientRect(g);
                // only presses that land on the game itself (not our bar or another window)
                if (r.IsEmpty || !r.Contains(x, y) || Game.RootAt(x, y) != g) return;
                current = new Stroke();
                currentRect = r;
                downTime = time;
                current.GapMs = strokes.Count == 0 ? FIRST_GAP_MS : Math.Max(0, unchecked((int)(time - lastUpTime)));
                AddPoint(x, y, 0);
            }
            else if (current != null && msg == MouseHook.MOVE)
            {
                if (Math.Abs(x - lastPoint.X) + Math.Abs(y - lastPoint.Y) >= MIN_STEP_PX)
                    AddPoint(x, y, unchecked((int)(time - downTime)));
            }
            else if (current != null && msg == MouseHook.UP)
            {
                AddPoint(x, y, unchecked((int)(time - downTime)));   // exact release point and hold time matter
                strokes.Add(current);
                recordedSize = currentRect.Size;
                lastUpTime = time;
                current = null;
                Save();
                SetStatus("REC · " + strokes.Count + " moves");
            }
        }

        void AddPoint(int x, int y, int t)
        {
            int prevT = current.Count == 0 ? 0 : current.T[current.Count - 1];
            current.T.Add(Math.Max(prevT, t));
            current.X.Add((x - currentRect.Left) / (float)currentRect.Width);
            current.Y.Add((y - currentRect.Top) / (float)currentRect.Height);
            lastPoint = new PointF(x, y);
        }

        void Undo()
        {
            if (strokes.Count == 0) return;
            strokes.RemoveAt(strokes.Count - 1);
            Save();
            SetStatus((recording ? "REC · " : "Ready · ") + strokes.Count + " moves");
        }

        void Save()
        {
            try { Recording.Save(Recording.DefaultPath, strokes, recordedSize); }
            catch (Exception e) { SetStatus("Save failed: " + e.Message); }
        }

        // ---------------------------------------------------------------- playback

        void StartPlaying(bool loop)
        {
            StopAll();
            if (strokes.Count == 0)
            {
                SetStatus("Nothing recorded yet");
                return;
            }
            IntPtr g = EnsureGame();
            if (g == IntPtr.Zero) return;
            Game.BringToFront(g);
            ResetTakeover();
            List<Stroke> plan = new List<Stroke>(strokes);
            playing = true;
            Thread t = new Thread(() => PlayLoop(g, plan, loop));
            t.IsBackground = true;
            t.Start();
        }

        void PlayLoop(IntPtr g, List<Stroke> plan, bool loop)
        {
            int run = 1;
            while (playing)
            {
                for (int i = 0; i < plan.Count; i++)
                {
                    Stroke s = plan[i];
                    if (!Wait(s.GapMs)) return;
                    Rectangle r = Game.ClientRect(g);
                    if (r.IsEmpty) { Finish("Stopped: the game window is closed or minimised"); return; }
                    if (!Game.InFront(g)) { Finish("Stopped: the game isn't the front window"); return; }
                    SetStatus((loop ? "Run " + run + " · " : "") + "move " + (i + 1) + "/" + plan.Count + " · F8 = stop");
                    List<Point> pts = new List<Point>(s.Count);
                    for (int j = 0; j < s.Count; j++)
                        pts.Add(new Point(r.Left + (int)Math.Round(s.X[j] * r.Width), r.Top + (int)Math.Round(s.Y[j] * r.Height)));
                    if (!Mouse.Drag(pts, s.T, () => !playing)) return;   // stopped; status already says why
                }
                if (!loop)
                {
                    Finish("Done · " + plan.Count + " moves");
                    return;
                }
                run++;
                SetStatus("Loop run " + run + " in " + LOOP_PAUSE_MS / 1000 + "s");
                if (!Wait(LOOP_PAUSE_MS)) return;
            }
        }

        bool Wait(long ms)
        {
            Stopwatch clock = Stopwatch.StartNew();
            while (playing && clock.ElapsedMilliseconds < ms)
                Thread.Sleep((int)Math.Max(1, Math.Min(20, ms - clock.ElapsedMilliseconds)));
            return playing;
        }

        void Finish(string why)
        {
            playing = false;
            SetStatus(why);
        }

        void StopAll()
        {
            CancelPick();
            playing = false;
            if (recording)
            {
                recording = false;
                current = null;   // a half-finished drag isn't saved
                Save();
            }
            SetStatus("Ready · " + strokes.Count + " moves");
        }
    }

    static class RecorderProgram
    {
        [STAThread]
        static void Main()
        {
            Startup.Init();
            bool created;
            using (Mutex one = new Mutex(true, "AngryBot.PC.Recorder", out created))
            {
                if (!created)
                {
                    MessageBox.Show("AngryBot is already running.", "AngryBot");
                    return;
                }
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new RecorderBar());
            }
        }
    }
}
