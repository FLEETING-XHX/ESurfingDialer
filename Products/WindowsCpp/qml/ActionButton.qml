import QtQuick
import QtQuick.Controls.Basic
Button {
    id: button
    property bool primary: false
    property bool danger: false
    implicitHeight: 40
    padding: 10
    font.family: "Noto Sans SC"; font.pixelSize: 13
    Accessible.name: text
    background: Rectangle {
        scale: Theme.motionEnabled && button.down ? 0.98 : 1
        Behavior on scale { NumberAnimation { duration: Theme.pressDuration; easing.type: Easing.OutCubic } }
        Behavior on color { ColorAnimation { duration: Theme.pressDuration } }
        radius: 10
        color: !button.enabled ? Theme.disabledSurface
             : button.primary ? (button.down ? Theme.accentPressed : button.hovered ? Theme.accentHover : Theme.accent)
             : button.down ? (button.danger ? Theme.dangerTint : Theme.accentTint)
             : button.hovered ? (button.danger ? Theme.dangerTint : Theme.hoverSurface)
             : Theme.panel
        border.width: button.visualFocus ? 2 : 0
        border.color: Theme.accent
    }
    contentItem: UiText {
        text: button.text
        color: !button.enabled ? Theme.muted : button.primary ? Theme.accentText : button.danger ? Theme.danger : Theme.ink
        horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter
    }
}
