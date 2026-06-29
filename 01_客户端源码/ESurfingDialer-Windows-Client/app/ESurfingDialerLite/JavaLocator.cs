using System.IO;

namespace ESurfingDialerLite;

public static class JavaLocator
{
    public static string FindJavaExe()
    {
        var bundled = Path.Combine(AppContext.BaseDirectory, "runtime", "bin", "java.exe");
        if (File.Exists(bundled)) return bundled;

        var localJdk21 = @"D:\Java\JDK21\bin\java.exe";
        if (File.Exists(localJdk21)) return localJdk21;

        return "java";
    }
}
