using System;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;

namespace AccountSplit2FA
{
    internal sealed class TransferSelectionDialog : Form
    {
        private readonly CheckedListBox list;
        private readonly List<HistoryEntry> entries;
        public List<HistoryEntry> Selected { get; private set; }
        public TransferSelectionDialog(string title, IList<HistoryEntry> source)
        {
            Text = title; Font = Theme.Font(10, FontStyle.Regular); ClientSize = new Size(500, 425); BackColor = Theme.Background;
            StartPosition = FormStartPosition.CenterParent; MinimizeBox = MaximizeBox = false; FormBorderStyle = FormBorderStyle.FixedDialog;
            entries = new List<HistoryEntry>(); foreach (HistoryEntry e in source) if (!e.IsExpired(DateTime.UtcNow)) entries.Add(e);
            Controls.Add(new Label { Text = "包含账号、密码、2FA 密钥及 otpauth 链接和历史设置。\n重复记录保留本机版本；已到期记录不导入或导出。", Bounds = new Rectangle(20, 16, 460, 50), ForeColor = Theme.Muted });
            list = new CheckedListBox { CheckOnClick = true, Bounds = new Rectangle(20, 74, 460, 280), BorderStyle = BorderStyle.FixedSingle, HorizontalScrollbar = true };
            foreach (HistoryEntry e in entries) list.Items.Add(e.DisplayName + (e.Pinned ? " · 已置顶" : "") + (e.ExpiresAtUtc.HasValue ? " · " + e.ExpiresAtUtc.Value.ToLocalTime().ToString("MM-dd HH:mm") + " 到期" : " · 永久"), false);
            Controls.Add(list);
            ActionButton all = new ActionButton { Text = "全选 / 取消", Kind = ButtonKind.Ghost, Bounds = new Rectangle(20, 370, 130, 36) };
            all.Click += delegate { bool check = list.CheckedItems.Count != list.Items.Count; for (int i = 0; i < list.Items.Count; i++) list.SetItemChecked(i, check); };
            ActionButton cancel = new ActionButton { Text = "取消", Kind = ButtonKind.Ghost, Bounds = new Rectangle(248, 370, 108, 36) };
            cancel.Click += delegate { Close(); };
            ActionButton ok = new ActionButton { Text = "继续", Kind = ButtonKind.Primary, Bounds = new Rectangle(370, 370, 110, 36) };
            ok.Click += delegate { Selected = new List<HistoryEntry>(); foreach (int i in list.CheckedIndices) if (!entries[i].IsExpired(DateTime.UtcNow)) Selected.Add(entries[i]); if (Selected.Count == 0) { MessageBox.Show(this, "请选择至少一条未到期记录。"); return; } DialogResult = DialogResult.OK; Close(); };
            Controls.AddRange(new Control[]{all, cancel, ok});
        }
    }
    internal sealed class TransferPasswordDialog : Form
    {
        private readonly TextBox password, confirmation;
        private readonly Label feedback;
        private readonly bool exporting;
        public string Password { get; private set; }
        public TransferPasswordDialog(bool exporting)
        {
            this.exporting = exporting; Text = exporting ? "设置导出文件密码" : "输入导入文件密码";
            ClientSize = new Size(430, 255); BackColor = Theme.Background; Font = Theme.Font(10, FontStyle.Regular);
            StartPosition = FormStartPosition.CenterParent; FormBorderStyle = FormBorderStyle.FixedDialog; MinimizeBox = MaximizeBox = false;
            Controls.Add(new Label { Text = "文件密码不会保存，导入时需要此密码。", Bounds = new Rectangle(20, 18, 390, 30), ForeColor = Theme.Muted });
            password = new TextBox { UseSystemPasswordChar = true, MaxLength = 1024, Bounds = new Rectangle(20, 57, 390, 30), AccessibleName = "文件密码" };
            confirmation = new TextBox { UseSystemPasswordChar = true, MaxLength = 1024, Bounds = new Rectangle(20, 112, 390, 30), AccessibleName = "再次输入文件密码", Visible = exporting };
            Controls.Add(new Label { Text = "再次输入密码", Bounds = new Rectangle(20, 89, 390, 24), Visible = exporting, ForeColor = Theme.Muted });
            feedback = new Label { Bounds = new Rectangle(20, 151, 390, 42), ForeColor = Color.Firebrick };
            ActionButton ok = new ActionButton { Text = "继续", Kind = ButtonKind.Primary, Bounds = new Rectangle(300, 205, 110, 34) }; ok.Click += delegate { Save(); };
            Controls.AddRange(new Control[]{password, confirmation, feedback, ok}); Shown += delegate { password.Focus(); };
        }
        private void Save() {
            if (password.TextLength == 0 || (exporting && password.Text != confirmation.Text)) { feedback.Text = "请输入密码，并确保两次输入一致。"; return; }
            Password = password.Text; password.Clear(); confirmation.Clear(); DialogResult = DialogResult.OK; Close();
        }
        protected override bool ProcessDialogKey(Keys key) { if (key == Keys.Enter) { Save(); return true; } if (key == Keys.Escape) { Close(); return true; } return base.ProcessDialogKey(key); }
    }
}
