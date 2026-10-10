using System.Globalization;
using System.Windows.Data;

namespace ESurfingDialerLite;

public sealed class TextLineHeightConverter : IValueConverter
{
    // Match the Pen text boxes while retaining room for Chinese glyphs.
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) =>
        value is double size ? Math.Round(size * 1.45) : 19d;

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}
