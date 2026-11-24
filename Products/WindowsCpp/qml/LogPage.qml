import QtQuick
import QtQuick.Layouts

PageScroll {
    id: page
    required property var client

    Card {
        width: page.bodyWidth
        height: list.implicitHeight + 14
        Column {
            id: list
            x: Theme.cardInset
            y: 7
            width: parent.width - 2 * Theme.cardInset

            UiText {
                visible: page.client.state.logs.length === 0
                text: "暂无运行记录"
                color: Theme.muted
                height: 54
                verticalAlignment: Text.AlignVCenter
            }

            Repeater {
                model: page.client.state.logs.slice().reverse()
                delegate: Column {
                    required property string modelData
                    required property int index
                    width: list.width

                    Rectangle {
                        visible: index > 0
                        width: parent.width
                        height: 1
                        color: Theme.border
                    }
                    Item {
                        width: parent.width
                        height: Math.max(49, logRow.implicitHeight + 26)
                        RowLayout {
                            id: logRow
                            anchors.left: parent.left
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            spacing: 12
                            Rectangle {
                                Layout.preferredWidth: 7
                                Layout.preferredHeight: 7
                                radius: 4
                                color: index === 0 ? Theme.accent : Theme.inactive
                            }
                            UiText {
                                Layout.fillWidth: true
                                text: modelData
                                wrapMode: Text.Wrap
                                elide: Text.ElideNone
                            }
                        }
                    }
                }
            }
        }
    }
}
