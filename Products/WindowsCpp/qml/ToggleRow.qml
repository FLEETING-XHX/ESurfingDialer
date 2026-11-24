import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

Item {
    id: row
    property string title
    property string description
    property bool checked: false
    signal toggled(bool value)
    implicitHeight: description ? 55 : 46

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: Theme.cardInset
        anchors.rightMargin: Theme.cardInset - (48 - 37) / 2
        spacing: 8

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 2
            UiText { Layout.fillWidth: true; text: row.title; font.bold: true }
            UiText {
                Layout.fillWidth: true
                visible: !!row.description
                text: row.description
                color: Theme.muted
                font.pixelSize: 12
            }
        }

        Switch {
            id: toggle
            Layout.preferredWidth: 48
            Layout.preferredHeight: 44
            checked: row.checked
            onToggled: row.toggled(checked)
            Accessible.name: row.title
            indicator: Rectangle {
                x: (toggle.width - width) / 2
                y: (toggle.height - height) / 2
                width: 37
                height: 22
                radius: 11
                color: toggle.checked ? Theme.success : Theme.inactive
                Behavior on color { ColorAnimation { duration: Theme.changeDuration } }
                border.width: toggle.visualFocus ? 2 : 0
                border.color: Theme.accent
                Rectangle {
                    x: toggle.checked ? 18 : 3
                    Behavior on x {
                        NumberAnimation { duration: Theme.changeDuration; easing.type: Easing.OutCubic }
                    }
                    y: 3
                    width: 16
                    height: 16
                    radius: 8
                    color: Theme.surface
                }
            }
            contentItem: Item {}
        }
    }
}
