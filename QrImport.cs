using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Reflection;
using System.Threading;
using ZXing;
using ZXing.Common;
using FormatException = System.FormatException;

namespace AccountSplit2FA
{
    internal static class QrDependencies
    {
        private static int registered;
        public static void Register()
        {
            if (Interlocked.Exchange(ref registered, 1) != 0) return;
            AppDomain.CurrentDomain.AssemblyResolve += delegate(object sender, ResolveEventArgs args) {
                if (new AssemblyName(args.Name).Name != "zxing") return null;
                using (Stream stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("AccountVerificationManager.zxing.dll"))
                {
                    if (stream == null) throw new InvalidOperationException("The embedded QR decoder is missing.");
                    using (MemoryStream bytes = new MemoryStream()) { stream.CopyTo(bytes); return Assembly.Load(bytes.ToArray()); }
                }
            };
        }
    }

    internal static class QrImageReader
    {
        public static IList<string> ReadFile(string path)
        {
            if (new FileInfo(path).Length > 32L * 1024 * 1024)
                throw new FormatException("图片过大，请选择 32 MB 以内的图片。");
            using (FileStream stream = File.OpenRead(path))
            using (Image image = Image.FromStream(stream, false, true))
            {
                if ((long)image.Width * image.Height > 64000000 || image.Width > 32768 || image.Height > 32768)
                    throw new FormatException("图片分辨率过大，请先裁剪二维码区域。");
                double scale = Math.Min(1.0, 2400.0 / Math.Max(image.Width, image.Height));
                using (Bitmap bitmap = new Bitmap(Math.Max(1, (int)(image.Width * scale)), Math.Max(1, (int)(image.Height * scale))))
                {
                    using (Graphics graphics = Graphics.FromImage(bitmap))
                    {
                        graphics.Clear(Color.White); graphics.InterpolationMode = InterpolationMode.HighQualityBicubic;
                        graphics.DrawImage(image, 0, 0, bitmap.Width, bitmap.Height);
                    }
                    return Decode(bitmap);
                }
            }
        }

        internal static IList<string> Decode(Bitmap bitmap)
        {
            BarcodeReader reader = new BarcodeReader {
                AutoRotate = true,
                Options = new DecodingOptions { TryHarder = true, TryInverted = true,
                    PossibleFormats = new List<BarcodeFormat> { BarcodeFormat.QR_CODE } }
            };
            Result[] decoded = reader.DecodeMultiple(bitmap);
            if (decoded == null || decoded.Length == 0)
            {
                Result single = reader.Decode(bitmap);
                decoded = single == null ? new Result[0] : new Result[] { single };
            }
            if (decoded.Length == 0) throw new FormatException("未发现二维码，请选择清晰图片，或先裁剪二维码区域。");
            List<string> supported = new List<string>(); HashSet<string> unique = new HashSet<string>(StringComparer.Ordinal);
            foreach (Result item in decoded)
            {
                if (String.IsNullOrEmpty(item.Text) || !unique.Add(item.Text)) continue;
                try { using (AccountData data = SmartParser.Parse(item.Text)) { supported.Add(item.Text); } }
                catch (FormatException) { }
                if (supported.Count >= 32) break;
            }
            if (supported.Count == 0)
                throw new FormatException("二维码内容不是支持的 2FA 密钥或 Authenticator URI；不支持批量迁移二维码。");
            return supported;
        }

        internal static string Describe(string text)
        {
            using (AccountData data = SmartParser.Parse(text))
                return (data.Issuer.Length > 0 ? data.Issuer + " · " : "") +
                    (data.Account.Length > 0 ? data.Account : "2FA 密钥") + " · " +
                    (data.OtpKind == OtpKind.Hotp ? "HOTP" : "TOTP");
        }
    }
}
