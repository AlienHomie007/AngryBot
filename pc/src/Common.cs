using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace AngryBot
{
    /// <summary>Win32 calls used by AngryBot.</summary>
    static class Native
    {
        [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
        [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }

        [StructLayout(LayoutKind.Sequential)]
        public struct MSLLHOOKSTRUCT { public POINT pt; public uint mouseData, flags, time; public IntPtr dwExtraInfo; }

        [StructLayout(LayoutKind.Sequential)]
        public struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr dwExtraInfo; }

        // MOUSEINPUT is the largest member of the INPUT union, so this has the right size on x86 and x64
        [StructLayout(LayoutKind.Sequential)]
        public struct INPUT { public uint type; public MOUSEINPUT mi; }

        public delegate IntPtr HookProc(int code, IntPtr wParam, IntPtr lParam);
        public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

        [DllImport("user32.dll", SetLastError = true)] public static extern IntPtr SetWindowsHookEx(int id, HookProc fn, IntPtr mod, uint thread);
        [DllImport("user32.dll")] public static extern bool UnhookWindowsHookEx(IntPtr hook);
        [DllImport("user32.dll")] public static extern IntPtr CallNextHookEx(IntPtr hook, int code, IntPtr wParam, IntPtr lParam);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] public static extern IntPtr GetModuleHandle(string name);
        [DllImport("user32.dll", SetLastError = true)] public static extern uint SendInput(uint count, INPUT[] inputs, int size);
        [DllImport("user32.dll")] public static extern int GetSystemMetrics(int index);
        [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc fn, IntPtr lParam);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder sb, int max);
        [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
        [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr h);
        [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
        [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
        [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
        [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
        [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
        [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
        [DllImport("user32.dll")] public static extern IntPtr WindowFromPoint(POINT p);
        [DllImport("user32.dll")] public static extern IntPtr GetAncestor(IntPtr h, uint flags);
        [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr context);
        [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
        [DllImport("user32.dll")] public static extern bool RegisterHotKey(IntPtr h, int id, uint mods, uint vk);
        [DllImport("user32.dll")] public static extern bool UnregisterHotKey(IntPtr h, int id);
        [DllImport("user32.dll")] public static extern bool ReleaseCapture();
        [DllImport("user32.dll")] public static extern IntPtr SendMessage(IntPtr h, int msg, IntPtr wParam, IntPtr lParam);
        [DllImport("winmm.dll")] public static extern uint timeBeginPeriod(uint ms);

        public const uint GA_ROOT = 2;
    }

    static class Startup
    {
        /// <summary>Work in real screen pixels on every monitor, and make Thread.Sleep accurate to ~1 ms.</summary>
        public static void Init()
        {
            bool aware = false;
            try { aware = Native.SetProcessDpiAwarenessContext(new IntPtr(-4)); }   // per-monitor v2
            catch (EntryPointNotFoundException) { }
            if (!aware) { try { Native.SetProcessDPIAware(); } catch (EntryPointNotFoundException) { } }
            try { Native.timeBeginPeriod(1); } catch (DllNotFoundException) { }
        }
    }

    /// <summary>
    /// The Google Play Games window that shows Angry Birds 2. All recorded positions are stored
    /// relative to its client area, so moving the window doesn't break a recording.
    /// </summary>
    static class Game
    {
        static IntPtr handle = IntPtr.Zero;

        public static IntPtr Current
        {
            get
            {
                IntPtr h = handle;
                return h != IntPtr.Zero && Native.IsWindow(h) ? h : IntPtr.Zero;
            }
        }

        public static void Use(IntPtr h) { handle = h; }

        /// <summary>
        /// Looks for a Google Play Games window: first one titled "Angry Birds", otherwise the
        /// biggest titled Play Games window that isn't the launcher. Browser tabs etc. are ignored.
        /// </summary>
        public static IntPtr Find()
        {
            IntPtr best = IntPtr.Zero, fallback = IntPtr.Zero;
            long fallbackArea = 0;
            int self = Process.GetCurrentProcess().Id;
            Dictionary<uint, bool> isPlayGames = new Dictionary<uint, bool>();
            Native.EnumWindows((h, l) =>
            {
                if (!Native.IsWindowVisible(h)) return true;
                string title = Title(h);
                if (title.Length == 0) return true;
                uint pid;
                Native.GetWindowThreadProcessId(h, out pid);
                if (pid == self) return true;
                bool pg;
                if (!isPlayGames.TryGetValue(pid, out pg)) isPlayGames[pid] = pg = IsPlayGamesProcess(pid);
                if (!pg) return true;
                if (title.IndexOf("Angry Birds", StringComparison.OrdinalIgnoreCase) >= 0)
                {
                    best = h;
                    return false;
                }
                if (title.IndexOf("Google Play Games", StringComparison.OrdinalIgnoreCase) < 0)
                {
                    Rectangle r = ClientRect(h);
                    long area = (long)r.Width * r.Height;
                    if (area > fallbackArea) { fallbackArea = area; fallback = h; }
                }
                return true;
            }, IntPtr.Zero);
            return best != IntPtr.Zero ? best : fallback;
        }

        static bool IsPlayGamesProcess(uint pid)
        {
            try
            {
                Process p = Process.GetProcessById((int)pid);
                if (p.ProcessName.Equals("crosvm", StringComparison.OrdinalIgnoreCase)) return true;
                try { return p.MainModule.FileName.IndexOf("\\Play Games\\", StringComparison.OrdinalIgnoreCase) >= 0; }
                catch (Exception) { return false; }   // no access to that process
            }
            catch (Exception)
            {
                return false;
            }
        }

        public static string Title(IntPtr h)
        {
            StringBuilder sb = new StringBuilder(256);
            Native.GetWindowText(h, sb, sb.Capacity);
            return sb.ToString();
        }

        /// <summary>The window's client area in screen pixels, or empty if it's gone or minimised.</summary>
        public static Rectangle ClientRect(IntPtr h)
        {
            if (h == IntPtr.Zero || !Native.IsWindow(h) || Native.IsIconic(h)) return Rectangle.Empty;
            Native.RECT r;
            if (!Native.GetClientRect(h, out r)) return Rectangle.Empty;
            Native.POINT p = new Native.POINT();
            if (!Native.ClientToScreen(h, ref p)) return Rectangle.Empty;
            return new Rectangle(p.X, p.Y, r.Right - r.Left, r.Bottom - r.Top);
        }

        /// <summary>The top-level window under a screen point.</summary>
        public static IntPtr RootAt(int x, int y)
        {
            Native.POINT p = new Native.POINT();
            p.X = x;
            p.Y = y;
            IntPtr h = Native.WindowFromPoint(p);
            return h == IntPtr.Zero ? IntPtr.Zero : Native.GetAncestor(h, Native.GA_ROOT);
        }

        public static bool InFront(IntPtr h)
        {
            IntPtr fg = Native.GetForegroundWindow();
            return fg != IntPtr.Zero && (fg == h || Native.GetAncestor(fg, Native.GA_ROOT) == h);
        }

        public static void BringToFront(IntPtr h)
        {
            if (Native.IsIconic(h)) Native.ShowWindow(h, 9);   // SW_RESTORE
            Native.SetForegroundWindow(h);
        }
    }

    /// <summary>
    /// Sends left-button mouse drags. Google Play Games turns a left-button drag into a finger
    /// on the touch screen. Every event carries Tag so our own hook can tell them from the user's.
    /// </summary>
    static class Mouse
    {
        public static readonly IntPtr Tag = new IntPtr(0x41425421);   // "ABT!"
        static readonly object gate = new object();

        const uint MOVE = 0x1, LEFTDOWN = 0x2, LEFTUP = 0x4, VIRTUALDESK = 0x4000, ABSOLUTE = 0x8000;

        static void Send(uint button, Point p)
        {
            int vx = Native.GetSystemMetrics(76), vy = Native.GetSystemMetrics(77);
            int vw = Native.GetSystemMetrics(78), vh = Native.GetSystemMetrics(79);
            Native.INPUT input = new Native.INPUT();
            input.type = 0;   // INPUT_MOUSE
            input.mi.dx = (int)Math.Round((p.X - vx) * 65535.0 / Math.Max(1, vw - 1));
            input.mi.dy = (int)Math.Round((p.Y - vy) * 65535.0 / Math.Max(1, vh - 1));
            input.mi.dwFlags = button | MOVE | ABSOLUTE | VIRTUALDESK;
            input.mi.dwExtraInfo = Tag;
            Native.SendInput(1, new Native.INPUT[] { input }, Marshal.SizeOf(typeof(Native.INPUT)));
        }

        /// <summary>
        /// Presses at pts[0], moves through the rest at their times (ms after the press) and
        /// releases at the last point. Returns false if cancelled part-way (the button is still released).
        /// </summary>
        public static bool Drag(IList<Point> pts, IList<int> times, Func<bool> cancelled)
        {
            lock (gate)
            {
                Point last = pts[0];
                Send(LEFTDOWN, last);
                Stopwatch clock = Stopwatch.StartNew();
                try
                {
                    for (int i = 1; i < pts.Count; i++)
                    {
                        int wait = times[i] - (int)clock.ElapsedMilliseconds;
                        if (wait > 0) Thread.Sleep(wait);
                        if (cancelled != null && cancelled()) return false;
                        last = pts[i];
                        Send(0, last);
                    }
                    return true;
                }
                finally
                {
                    Send(LEFTUP, last);
                }
            }
        }
    }

    /// <summary>Global low-level mouse hook. Must be created on a thread with a message loop.</summary>
    sealed class MouseHook : IDisposable
    {
        public const int MOVE = 0x200, DOWN = 0x201, UP = 0x202;

        /// <summary>Return true to swallow the event. ours = sent by Mouse, not by the user.</summary>
        public delegate bool Handler(int msg, int x, int y, uint time, bool ours);

        readonly Native.HookProc proc;   // kept in a field so the GC can't collect it
        readonly Handler handler;
        IntPtr hook;

        public MouseHook(Handler handler)
        {
            this.handler = handler;
            proc = Callback;
            hook = Native.SetWindowsHookEx(14, proc, Native.GetModuleHandle(null), 0);   // WH_MOUSE_LL
            if (hook == IntPtr.Zero) throw new InvalidOperationException("Couldn't watch the mouse (error " + Marshal.GetLastWin32Error() + ")");
        }

        IntPtr Callback(int code, IntPtr wParam, IntPtr lParam)
        {
            if (code >= 0)
            {
                int msg = wParam.ToInt32();
                if (msg == MOVE || msg == DOWN || msg == UP)
                {
                    Native.MSLLHOOKSTRUCT m = (Native.MSLLHOOKSTRUCT)Marshal.PtrToStructure(lParam, typeof(Native.MSLLHOOKSTRUCT));
                    bool swallow = false;
                    try { swallow = handler(msg, m.pt.X, m.pt.Y, m.time, m.dwExtraInfo == Mouse.Tag); }
                    catch (Exception) { }   // never let an error break the user's mouse
                    if (swallow) return new IntPtr(1);
                }
            }
            return Native.CallNextHookEx(hook, code, wParam, lParam);
        }

        public void Dispose()
        {
            if (hook != IntPtr.Zero) { Native.UnhookWindowsHookEx(hook); hook = IntPtr.Zero; }
        }
    }

    /// <summary>A button that never takes keyboard focus, so clicking it can't pull focus from the game.</summary>
    sealed class FlatButton : Button
    {
        public FlatButton() { SetStyle(ControlStyles.Selectable, false); }
    }

    /// <summary>
    /// Floating control bar. It stays on top, never takes focus away from the game,
    /// and runs OnStopKey when F8 is pressed anywhere.
    /// </summary>
    class Bar : Form
    {
        readonly FlowLayoutPanel body;
        readonly Label status;
        readonly Color buttonBack;

        // ⌖ Pick: the next click on another window chooses the game
        bool picking, swallowUp;

        // "the user grabbed the mouse" detection
        Point lastPos;
        int travel, lastRealTick;

        public Bar(string title, Color back, Color glow)
        {
            Text = title;
            FormBorderStyle = FormBorderStyle.None;
            StartPosition = FormStartPosition.Manual;
            Location = new Point(16, 140);
            TopMost = true;
            BackColor = back;
            Opacity = 0.94;
            AutoSize = true;
            AutoSizeMode = AutoSizeMode.GrowAndShrink;
            Padding = new Padding(4);
            Font = new Font("Segoe UI Symbol", 9f);
            Icon = AppIcon.Make(back, glow);
            buttonBack = ControlPaint.Light(back, 0.4f);

            body = new FlowLayoutPanel();
            body.FlowDirection = FlowDirection.TopDown;
            body.WrapContents = false;
            body.AutoSize = true;
            body.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            body.Margin = Padding.Empty;
            Controls.Add(body);

            FlowLayoutPanel top = AddRow();
            Label handle = new Label();
            handle.Text = "⠿ " + title;
            handle.ForeColor = Color.White;
            handle.AutoSize = true;
            handle.Padding = new Padding(2, 6, 12, 6);
            handle.Cursor = Cursors.SizeAll;
            handle.MouseDown += (s, e) =>
            {
                if (e.Button != MouseButtons.Left) return;
                Native.ReleaseCapture();
                Native.SendMessage(Handle, 0xA1, new IntPtr(2), IntPtr.Zero);   // drag like a title bar
            };
            top.Controls.Add(handle);
            AddButton(top, "✕", (s, e) => Close());

            status = new Label();
            status.ForeColor = Color.FromArgb(0xFF, 0xD5, 0x4F);
            status.Font = new Font("Segoe UI Symbol", 8.5f);
            status.AutoSize = true;
            status.MaximumSize = new Size(330, 0);
            status.Padding = new Padding(2, 4, 2, 2);
            body.Controls.Add(status);
        }

        protected FlowLayoutPanel AddRow()
        {
            FlowLayoutPanel row = new FlowLayoutPanel();
            row.FlowDirection = FlowDirection.LeftToRight;
            row.WrapContents = false;
            row.AutoSize = true;
            row.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            row.Margin = Padding.Empty;
            body.Controls.Add(row);
            if (status != null) body.Controls.SetChildIndex(status, body.Controls.Count - 1);
            return row;
        }

        protected Button AddButton(Control row, string text, EventHandler click)
        {
            FlatButton b = new FlatButton();
            b.Text = text;
            b.AutoSize = true;
            b.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            b.FlatStyle = FlatStyle.Flat;
            b.FlatAppearance.BorderColor = ControlPaint.Light(buttonBack, 0.3f);
            b.BackColor = buttonBack;
            b.ForeColor = Color.White;
            b.UseVisualStyleBackColor = false;
            b.Margin = new Padding(2);
            b.Padding = new Padding(2, 0, 2, 0);
            b.Click += click;
            row.Controls.Add(b);
            return b;
        }

        public void SetStatus(string s)
        {
            if (IsDisposed) return;
            if (InvokeRequired)
            {
                try { BeginInvoke(new Action<string>(SetStatus), s); }
                catch (InvalidOperationException) { }   // closing
                return;
            }
            status.Text = s;
        }

        /// <summary>The game window, finding it first if needed; shows a hint and returns zero if there is none.</summary>
        protected IntPtr EnsureGame()
        {
            IntPtr g = Game.Current;
            if (g == IntPtr.Zero)
            {
                g = Game.Find();
                if (g != IntPtr.Zero) Game.Use(g);
            }
            if (g == IntPtr.Zero)
                SetStatus("Can't find Angry Birds 2. Open it in Google Play Games, or tap ⌖ Pick and click the game.");
            return g;
        }

        protected void StartPick()
        {
            picking = true;
            SetStatus("Click anywhere on the game window…");
        }

        /// <summary>Call for every hooked event; returns true when the event was a Pick click to swallow.</summary>
        protected bool HandlePick(int msg, int x, int y, bool ours)
        {
            if (ours) return false;
            if (swallowUp && msg == MouseHook.UP) { swallowUp = false; return true; }
            if (!picking || msg != MouseHook.DOWN) return false;
            IntPtr root = Game.RootAt(x, y);
            if (root == IntPtr.Zero || root == Handle) return false;   // a click on our own bar
            picking = false;
            swallowUp = true;
            Game.Use(root);
            SetStatus("Game window: " + Game.Title(root));
            return true;
        }

        protected void CancelPick() { picking = false; }

        protected void ResetTakeover() { travel = 0; }

        /// <summary>
        /// Call for every hooked event. True once the user clicks, or moves the mouse more than
        /// a few pixels within a second: they want control back.
        /// </summary>
        protected bool UserTookOver(int msg, int x, int y, bool ours)
        {
            Point prev = lastPos;
            lastPos = new Point(x, y);
            if (ours) return false;
            if (msg == MouseHook.DOWN) return true;
            if (msg != MouseHook.MOVE) return false;
            int now = Environment.TickCount;
            if (now - lastRealTick > 1000) travel = 0;
            lastRealTick = now;
            travel += Math.Abs(x - prev.X) + Math.Abs(y - prev.Y);
            return travel > 40;
        }

        protected virtual void OnStopKey() { }

        protected override bool ShowWithoutActivation { get { return true; } }

        protected override CreateParams CreateParams
        {
            get
            {
                CreateParams cp = base.CreateParams;
                cp.ExStyle |= 0x08000000 | 0x00040000;   // WS_EX_NOACTIVATE | WS_EX_APPWINDOW (keeps a taskbar button)
                return cp;
            }
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            Native.RegisterHotKey(Handle, 1, 0x4000, 0x77);   // MOD_NOREPEAT, F8
        }

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            Native.UnregisterHotKey(Handle, 1);
            base.OnFormClosed(e);
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == 0x21) { m.Result = new IntPtr(3); return; }   // WM_MOUSEACTIVATE -> MA_NOACTIVATE
            if (m.Msg == 0x312) { OnStopKey(); return; }               // WM_HOTKEY
            base.WndProc(ref m);
        }
    }

    /// <summary>Where AngryBot keeps its files: %APPDATA%\AngryBot.</summary>
    static class Storage
    {
        public static string Folder
        {
            get
            {
                string d = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AngryBot");
                Directory.CreateDirectory(d);
                return d;
            }
        }

        public static string F(float v) { return v.ToString("0.#####", CultureInfo.InvariantCulture); }

        public static float ParseF(string s) { return float.Parse(s, CultureInfo.InvariantCulture); }
    }

    /// <summary>The AngryBot logo (angry robot bird), drawn in code so the app is a single .exe.</summary>
    static class AppIcon
    {
        public static Icon Make(Color back, Color glow)
        {
            Bitmap b = new Bitmap(64, 64);
            using (Graphics g = Graphics.FromImage(b))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.ScaleTransform(64f / 72f, 64f / 72f);   // same 72x72 crop of the 108x108 artwork as logo.svg
                g.TranslateTransform(-18, -18);
                using (GraphicsPath p = Rounded(18, 18, 72, 72, 16))
                using (SolidBrush br = new SolidBrush(back)) g.FillPath(br, p);
                Circle(g, glow, 54, 58, 36);
                using (Pen pen = new Pen(Hex(0xB0BEC5), 2.5f))
                {
                    pen.StartCap = pen.EndCap = LineCap.Round;
                    g.DrawLine(pen, 54, 37, 54, 28);
                }
                Circle(g, Hex(0x00E5FF), 54, 26, 3);
                Circle(g, Hex(0xE53935), 54, 60, 24);
                using (SolidBrush br = new SolidBrush(Hex(0xFFCCBC))) g.FillEllipse(br, 40, 68, 28, 14);
                Circle(g, Hex(0xB0BEC5), 32, 62, 2);
                Circle(g, Hex(0xB0BEC5), 76, 62, 2);
                Circle(g, Color.White, 45, 56, 7);
                Circle(g, Color.White, 63, 56, 7);
                Circle(g, Hex(0x0D1B2A), 46.5f, 57, 3.5f);
                Circle(g, Hex(0x0D1B2A), 61.5f, 57, 3.5f);
                Circle(g, Hex(0x00E5FF), 46.6f, 57, 1.6f);
                Circle(g, Hex(0x00E5FF), 61.4f, 57, 1.6f);
                Poly(g, Hex(0x1A1A1A), 35, 45, 53, 50, 52, 54, 34, 49);
                Poly(g, Hex(0x1A1A1A), 73, 45, 55, 50, 56, 54, 74, 49);
                Poly(g, Hex(0xFF8F00), 48, 67, 60, 67, 54, 73);
                Poly(g, Hex(0xFFC107), 46, 63, 62, 63, 54, 69);
            }
            return Icon.FromHandle(b.GetHicon());
        }

        static Color Hex(int rgb) { return Color.FromArgb(255, Color.FromArgb(rgb)); }

        static void Circle(Graphics g, Color c, float cx, float cy, float r)
        {
            using (SolidBrush br = new SolidBrush(c)) g.FillEllipse(br, cx - r, cy - r, 2 * r, 2 * r);
        }

        static void Poly(Graphics g, Color c, params float[] xy)
        {
            PointF[] pts = new PointF[xy.Length / 2];
            for (int i = 0; i < pts.Length; i++) pts[i] = new PointF(xy[2 * i], xy[2 * i + 1]);
            using (SolidBrush br = new SolidBrush(c)) g.FillPolygon(br, pts);
        }

        static GraphicsPath Rounded(float x, float y, float w, float h, float r)
        {
            GraphicsPath p = new GraphicsPath();
            p.AddArc(x, y, 2 * r, 2 * r, 180, 90);
            p.AddArc(x + w - 2 * r, y, 2 * r, 2 * r, 270, 90);
            p.AddArc(x + w - 2 * r, y + h - 2 * r, 2 * r, 2 * r, 0, 90);
            p.AddArc(x, y + h - 2 * r, 2 * r, 2 * r, 90, 90);
            p.CloseFigure();
            return p;
        }
    }
}
