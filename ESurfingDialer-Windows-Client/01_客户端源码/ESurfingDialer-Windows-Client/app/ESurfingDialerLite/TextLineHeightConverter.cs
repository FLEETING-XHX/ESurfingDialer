using System.Globalization;
using System.Windows.Data;

namespace ESurfingDialerLite;

public sealed class TextLineHeightConverter : IValueConverter
{
    // Noto's native font metrics are taller than the design's compact text boxes.
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) =>
        value is double size ? Math.Ceiling(size * 1.3) : 18d;

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}
