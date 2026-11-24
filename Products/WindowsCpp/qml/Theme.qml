pragma Singleton
import QtQuick

QtObject {
    property bool motionEnabled: false
    readonly property int pressDuration: motionEnabled ? 85 : 0
    readonly property int changeDuration: motionEnabled ? 150 : 0
    readonly property int popupDuration: motionEnabled ? 170 : 0
    readonly property color surface: "#FFFFFF"
    readonly property color panel: "#FFFFFF"
    readonly property color hoverSurface: "#F5F7FA"
    readonly property int pageInset: 20
    readonly property int cardInset: 18
    readonly property color ink: "#292C31"
    readonly property color muted: "#5E646D"
    readonly property color accent: "#EF806F"
    readonly property color accentHover: "#F69787"
    readonly property color accentPressed: "#E87968"
    readonly property color accentTint: "#FFF0ED"
    readonly property color accentText: ink
    readonly property color accentInk: "#A34336"
    readonly property color success: "#43B856"
    readonly property color successTint: "#EEF8EF"
    readonly property color danger: "#A52B35"
    readonly property color dangerTint: "#FBE7E8"
    readonly property color inactive: "#AEB3BA"
    readonly property color fieldBorder: "#B4B9C2"
    readonly property color disabledSurface: "#EBECF0"
    readonly property color scrim: "#66292C31"
    readonly property color border: "#E7EAF0"
    readonly property color shadowNear: "#0A25354C"
    readonly property color shadowFar: "#0625354C"
    readonly property color nav: surface
    readonly property color navBorder: "#DDE0E5"
    readonly property color download: "#257539"
    readonly property color upload: accentInk
    readonly property color downloadTrend: "#9DD7AB"
    readonly property color uploadTrend: "#F0A294"
}
