import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

PageScroll {
    id: page
    required property var client
    required property bool previewMode
    signal manageAccounts(bool addNew)

    readonly property var state: client.state
    readonly property bool active: state.phase === "已连接" || state.phase === "连接中"
                                   || state.phase === "恢复中" || state.phase === "正在停止"
    readonly property bool hasAccounts: state.accounts.length > 0
    property bool triedConnection: false

    Card {
        width: page.bodyWidth
        height: connection.implicitHeight + 42
        radius: 22

        ColumnLayout {
            id: connection
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.margins: Theme.cardInset
            spacing: 12

            RowLayout {
                Layout.fillWidth: true
                spacing: 14

                Rectangle {
                    Layout.preferredWidth: 48
                    Layout.preferredHeight: 48
                    radius: 14
                    color: Theme.successTint
                    Icon {
                        anchors.centerIn: parent
                        name: "PenCampusIcon"
                        variant: page.state.online ? "success" : ""
                        width: 24
                        height: 24
                    }
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 2
                    UiText { text: "校园网"; color: Theme.muted; font.pixelSize: 13 }
                    UiText {
                        Layout.fillWidth: true
                        text: page.previewMode && page.state.phase === "尚未接入" ? "未连接" : page.state.phase
                        font.pixelSize: 27
                        font.bold: true
                    }
                }
            }

            UiText {
                Layout.fillWidth: true
                Layout.bottomMargin: 4
                text: page.previewMode
                    ? (page.hasAccounts ? "当前处于界面预览。" : "添加校园网账户后即可预览连接入口。")
                    : (page.hasAccounts || page.active ? page.state.status : "添加校园网账户后即可连接。")
                color: Theme.muted
                wrapMode: Text.Wrap
                elide: Text.ElideNone
                font.pixelSize: 13
            }

            ActionButton {
                Layout.fillWidth: true
                Layout.preferredHeight: 48
                enabled: page.state.phase !== "正在停止"
                primary: !page.active
                text: page.active ? (page.state.phase === "正在停止" ? "正在停止…" : "断开连接")
                    : !page.hasAccounts ? "添加账户"
                    : page.triedConnection ? "重试连接" : "连接校园网"
                onClicked: {
                    if (!page.active && !page.hasAccounts) {
                        page.manageAccounts(true)
                    } else {
                        page.triedConnection = !page.active
                        page.client.toggleConnection()
                    }
                }
            }
        }
    }

    Button {
        id: accountButton
        width: page.bodyWidth
        height: 70
        padding: Theme.cardInset
        Accessible.name: "管理校园网账户"
        onClicked: page.manageAccounts(false)

        background: Card {
            color: accountButton.hovered ? Theme.accentTint : Theme.panel
            border.width: accountButton.visualFocus ? 2 : 1
            border.color: accountButton.visualFocus ? Theme.accent : Theme.border
        }

        contentItem: Item {
            RowLayout {
                anchors.fill: parent
                spacing: 12
                Icon { name: "PenUserIcon" }
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 3
                    UiText { text: "当前账户"; color: Theme.muted; font.pixelSize: 12 }
                    UiText {
                        Layout.fillWidth: true
                        text: page.state.accountSummary
                        font.pixelSize: 14
                        font.bold: true
                    }
                }
                UiText { text: "管理"; color: Theme.accentInk; font.bold: true }
                Icon { name: "PenChevronIcon"; variant: "accent"; width: 15; height: 15 }
            }
        }
    }

    Card {
        width: page.bodyWidth
        height: networkGroup.implicitHeight + 36
        ColumnLayout {
            id: networkGroup
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.margins: Theme.cardInset
            spacing: 13

            UiText { text: "网络流量"; font.pixelSize: 17; font.bold: true }
            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                uniformCellSizes: true
                Repeater {
                    model: [
                        {label: "下载", key: "downloadKB", color: Theme.download},
                        {label: "上传", key: "uploadKB", color: Theme.upload}
                    ]
                    ColumnLayout {
                        required property var modelData
                        Layout.fillWidth: true
                        Layout.preferredWidth: 1
                        spacing: 4
                        UiText { Layout.fillWidth: true; text: modelData.label; color: Theme.muted; font.pixelSize: 12 }
                        UiText {
                            Layout.fillWidth: true
                            text: page.state.traffic.available ? page.state.traffic[modelData.key].toFixed(1) + " KB/s" : "—"
                            color: modelData.color
                            font.bold: true
                            font.pixelSize: 19
                        }
                    }
                }
            }
            UiText {
                visible: !page.state.traffic.available
                text: "暂无实时流量数据"
                color: Theme.muted
                font.pixelSize: 12
            }
            Canvas {
                Layout.fillWidth: true
                Layout.preferredHeight: 45
                visible: traffic.available
                property var traffic: page.state.traffic
                onTrafficChanged: if (visible) requestPaint()
                onWidthChanged: if (visible) requestPaint()
                onVisibleChanged: if (visible) requestPaint()
                onPaint: {
                    const ctx = getContext("2d")
                    ctx.reset()
                    const peak = Math.max(1, ...traffic.download, ...traffic.upload)
                    function line(values, color) {
                        if (values.length < 2)
                            return
                        ctx.beginPath()
                        ctx.strokeStyle = color
                        ctx.lineWidth = 2
                        for (let i = 0; i < values.length; i++) {
                            const x = i * width / (values.length - 1)
                            const y = height - 2 - values[i] / peak * (height - 4)
                            if (i === 0)
                                ctx.moveTo(x, y)
                            else
                                ctx.lineTo(x, y)
                        }
                        ctx.stroke()
                    }
                    line(traffic.download, Theme.downloadTrend)
                    line(traffic.upload, Theme.uploadTrend)
                }
            }
        }
    }
}
