import QtQuick
import QtQuick.Controls.Basic
TextField {
    id: field
    implicitHeight: 38
    leftPadding: 12; rightPadding: 12
    color: Theme.ink
    font.family: "Noto Sans SC"; font.pixelSize: 13
    selectByMouse: true
    selectionColor: Theme.accent
    selectedTextColor: Theme.accentText
    background: Rectangle {
        radius: 8
        color: field.enabled ? Theme.surface : Theme.disabledSurface
        border.color: field.activeFocus ? Theme.accent : Theme.fieldBorder
        border.width: field.activeFocus ? 1.5 : 1
    }
}
