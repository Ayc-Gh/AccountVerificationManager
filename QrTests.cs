using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Reflection;
using AccountSplit2FA;
using ZXing;
using ZXing.Common;
using FormatException = System.FormatException;

internal static class QrTests
{
    private const string Secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private const string UriText = "otpauth://totp/Example:qr%40example.com?secret=" + Secret + "&issuer=Example&algorithm=SHA256&digits=8&period=60";
    private static int passed;
    private static void Check(bool condition, string label) { if (!condition) throw new Exception("FAIL: " + label); passed++; }
    private static void Reject(Action action, string label)
    {
        try { action(); } catch (FormatException) { passed++; return; }
        catch (ArgumentException) { passed++; return; } catch (IOException) { passed++; return; }
        throw new Exception("FAIL: " + label);
    }
    private static void Main() { QrDependencies.Register(); Run(); }
    [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
    private static void Run()
    {
        string folder = Path.Combine(Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location), "qr-fixtures");
        Directory.CreateDirectory(folder);
        using (Bitmap image = Make(Secret)) image.Save(Path.Combine(folder, "pure.png"));
        Check(QrImageReader.ReadFile(Path.Combine(folder, "pure.png"))[0] == Secret, "pure Base32 QR PNG");
        using (Bitmap image = Make(UriText))
        {
            image.Save(Path.Combine(folder, "uri.png"));
            image.Save(Path.Combine(folder, "uri.jpg"), ImageFormat.Jpeg);
            image.RotateFlip(RotateFlipType.Rotate90FlipNone); image.Save(Path.Combine(folder, "rotated.png"));
        }
        foreach (string file in new string[] { "uri.png", "uri.jpg", "rotated.png" })
        {
            IList<string> values = QrImageReader.ReadFile(Path.Combine(folder, file));
            Check(values.Count == 1 && values[0] == UriText, "URI image round trip " + file);
            using (AccountData data = SmartParser.Parse(values[0]))
                Check(data.Account == "qr@example.com" && data.Digits == 8 && data.Period == 60 && data.Algorithm == "SHA256", "QR retains URI parameters " + file);
        }
        using (Bitmap image = Make(Secret))
        {
            using (Bitmap inverted = new Bitmap(image.Width, image.Height))
            using (Graphics graphics = Graphics.FromImage(inverted))
            using (ImageAttributes attributes = new ImageAttributes())
            {
                attributes.SetColorMatrix(new ColorMatrix(new float[][] {
                    new float[]{-1,0,0,0,0}, new float[]{0,-1,0,0,0}, new float[]{0,0,-1,0,0},
                    new float[]{0,0,0,1,0}, new float[]{1,1,1,0,1} }));
                graphics.DrawImage(image, new Rectangle(0, 0, image.Width, image.Height), 0, 0, image.Width, image.Height, GraphicsUnit.Pixel, attributes);
                inverted.Save(Path.Combine(folder, "inverted.png"));
            }
        }
        Check(QrImageReader.ReadFile(Path.Combine(folder, "inverted.png"))[0] == Secret, "inverted QR");
        using (Bitmap first = Make(Secret)) using (Bitmap second = Make(UriText))
        using (Bitmap image = new Bitmap(780, 400)) using (Graphics graphics = Graphics.FromImage(image))
        {
            graphics.Clear(Color.White); graphics.DrawImageUnscaled(first, 20, 20); graphics.DrawImageUnscaled(second, 400, 20);
            image.Save(Path.Combine(folder, "multiple.png"));
        }
        Check(QrImageReader.ReadFile(Path.Combine(folder, "multiple.png")).Count == 2, "multiple QR choices retained");
        using (Bitmap image = new Bitmap(360, 360)) { using (Graphics graphics = Graphics.FromImage(image)) graphics.Clear(Color.White); image.Save(Path.Combine(folder, "blank.png")); }
        Reject(() => QrImageReader.ReadFile(Path.Combine(folder, "blank.png")), "blank image rejects");
        using (Bitmap image = Make("https://example.com/")) image.Save(Path.Combine(folder, "web.png"));
        Reject(() => QrImageReader.ReadFile(Path.Combine(folder, "web.png")), "web QR is not imported");
        using (Bitmap image = Make("otpauth-migration://offline?data=AA")) image.Save(Path.Combine(folder, "migration.png"));
        Reject(() => QrImageReader.ReadFile(Path.Combine(folder, "migration.png")), "migration QR is not misread");
        using (Bitmap image = Make("otpauth://hotp/test?secret=" + Secret + "&counter=2")) image.Save(Path.Combine(folder, "hotp.png"));
        using (AccountData data = SmartParser.Parse(QrImageReader.ReadFile(Path.Combine(folder, "hotp.png"))[0]))
            Check(data.OtpKind == OtpKind.Hotp && data.Counter == 2, "Windows HOTP QR retains counter");
        File.WriteAllText(Path.Combine(folder, "broken.png"), "synthetic invalid image");
        Reject(() => QrImageReader.ReadFile(Path.Combine(folder, "broken.png")), "malformed image rejects");
        Check(!QrImageReader.Describe(Secret).Contains(Secret), "choice label does not expose key");
        Check(QrImageReader.Describe(UriText).Contains("qr@example.com"), "choice label identifies account");
        Console.WriteLine("PASS: " + passed + " QR checks; PNG/JPEG, rotation, inversion, multiple codes, OTP parameters, invalid inputs and embedded decoder.");
    }
    private static Bitmap Make(string text)
    {
        return new BarcodeWriter { Format = BarcodeFormat.QR_CODE,
            Options = new EncodingOptions { Width = 360, Height = 360, Margin = 4 } }.Write(text);
    }
}
