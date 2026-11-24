import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

PageScroll {
    id: page
    required property var client
    signal showDetails()

    function setFlag(key, value) {
        const change = {}
        change[key] = value
        client.saveSettings(change)
    }

    Card {
        width: page.bodyWidth
        height: startup.implicitHeight + 20
        Column {
            id: startup
            y: 10
            width: parent.width
            UiText { x: Theme.cardInset; text: "启动"; height: 30; font.pixelSize: 16; font.bold: true }
            ToggleRow { width: parent.width; title: "开机自启"; checked: page.client.state.settings.autoStart; onToggled: value => page.setFlag("autoStart", value) }
            ToggleRow { visible: page.client.state.settings.autoStart; width: parent.width; title: "启动后最小化到后台"; checked: page.client.state.settings.startMinimized; onToggled: value => page.setFlag("startMinimized", value) }
            ToggleRow { width: parent.width; title: "自动连接校园网"; checked: page.client.state.settings.autoConnect; onToggled: value => page.setFlag("autoConnect", value) }
        }
    }

    Card {
        width: page.bodyWidth
        height: network.implicitHeight + 20
        Column {
            id: network
            y: 10
            width: parent.width
            UiText { x: Theme.cardInset; text: "连接"; height: 30; font.pixelSize: 16; font.bold: true }
            ToggleRow { width: parent.width; title: "增强连接"; description: "断线后尝试恢复认证"; checked: page.client.state.settings.enhancedConnection; onToggled: value => page.setFlag("enhancedConnection", value) }
            ToggleRow { width: parent.width; title: "后台运行提示"; checked: page.client.state.settings.backgroundNotice; onToggled: value => page.setFlag("backgroundNotice", value) }
            ToggleRow { width: parent.width; title: "每日重新登录"; description: "按 UTC+8 设置重登时间"; checked: page.client.state.settings.dailyReauth; onToggled: value => page.setFlag("dailyReauth", value) }

            ColumnLayout {
                x: Theme.cardInset
                width: parent.width - 2 * Theme.cardInset
                spacing: 9
                RowLayout {
                    Layout.fillWidth: true
                    spacing: 12
                    ColumnLayout {
                        Layout.fillWidth: true
                        UiText { text: "开始时间（整点）"; font.pixelSize: 12; color: Theme.muted }
                        FormField {
                            id: start
                            Layout.fillWidth: true
                            Layout.preferredWidth: 1
                            text: page.client.state.settings.startHour
                            validator: IntValidator { bottom: 0; top: 23 }
                            Accessible.name: "每日重登开始时间"
                        }
                    }
                    ColumnLayout {
                        Layout.fillWidth: true
                        UiText { text: "结束时间（整点）"; font.pixelSize: 12; color: Theme.muted }
                        FormField {
                            id: end
                            Layout.fillWidth: true
                            Layout.preferredWidth: 1
                            text: page.client.state.settings.endHour
                            validator: IntValidator { bottom: 0; top: 24 }
                            Accessible.name: "每日重登结束时间"
                        }
                    }
                }
                ActionButton {
                    Layout.fillWidth: true
                    text: "保存重登时间"
                    onClicked: page.client.saveSettings({startHour: start.text, endHour: end.text})
                }
            }
        }
    }

    Card {
        width: page.bodyWidth
        height: services.implicitHeight + 20
        Column {
            id: services
            y: 10
            x: 10
            width: parent.width - 20
            UiText { x: Theme.cardInset - services.x; text: "支持与服务"; height: 34; font.pixelSize: 16; font.bold: true }
            Repeater {
                model: [
                    {title: "查看详细日志", action: "details"},
                    {title: "导出日志", action: "export"},
                    {title: "GitHub 项目主页", action: "project"},
                    {title: "修改密码", action: "password"},
                    {title: "宽带充值", action: "recharge"}
                ]
                delegate: Button {
                    id: action
                    required property var modelData
                    width: services.width
                    height: 44
                    leftPadding: Theme.cardInset - services.x
                    rightPadding: leftPadding
                    Accessible.name: modelData.title
                    onClicked: modelData.action === "details" ? page.showDetails() : page.client.action(modelData.action)
                    background: Rectangle {
                        radius: 9
                        color: action.hovered ? Theme.accentTint : "transparent"
                        border.width: action.visualFocus ? 2 : 0
                        border.color: Theme.accent
                    }
                    contentItem: Item {
                        RowLayout {
                            anchors.fill: parent
                            UiText { Layout.fillWidth: true; text: action.modelData.title }
                            Icon { name: "PenChevronIcon"; width: 16; height: 16 }
                        }
                    }
                }
            }
        }
    }
}
