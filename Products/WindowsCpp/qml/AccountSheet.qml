import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

Popup {
    id: sheet
    required property var client
    required property bool previewMode
    property bool editing: false
    property string editingId: ""
    property string pendingDelete: ""
    property string errorText: ""

    width: Math.min(460, parent.width - 24)
    height: Math.min(editing ? 430 : 460, parent.height - 40)
    x: (parent.width - width) / 2
    y: parent.height - height - 12
    padding: 18
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    Overlay.modal: Rectangle { color: Theme.scrim }
    background: Card { radius: 22 }
    enter: Transition {
        ParallelAnimation {
            NumberAnimation { property: "opacity"; from: Theme.motionEnabled ? 0 : 1; to: 1; duration: Theme.popupDuration }
            NumberAnimation { property: "scale"; from: Theme.motionEnabled ? 0.98 : 1; to: 1; duration: Theme.popupDuration; easing.type: Easing.OutCubic }
        }
    }
    exit: Transition {
        NumberAnimation { property: "opacity"; from: 1; to: 0; duration: Theme.pressDuration }
    }

    onAboutToShow: {
        editing = false
        pendingDelete = ""
        errorText = ""
    }

    onClosed: {
        secret.clear()
        editingId = ""
        pendingDelete = ""
        errorText = ""
    }

    function edit(account) {
        editingId = account ? account.id : ""
        remark.text = account ? account.label : ""
        user.text = account ? account.username : ""
        secret.clear()
        errorText = ""
        editing = true
        user.forceActiveFocus()
    }

    function openNew() {
        open()
        edit(null)
    }

    contentItem: ColumnLayout {
        spacing: 13
        RowLayout {
            Layout.fillWidth: true
            UiText {
                Layout.fillWidth: true
                text: sheet.pendingDelete ? "删除账户" : sheet.editing
                    ? (sheet.editingId ? "编辑账户" : "添加账户") : "账户管理"
                font.pixelSize: 22
                font.bold: true
            }
            ActionButton {
                visible: !sheet.editing && !sheet.pendingDelete
                text: "添加"
                primary: true
                onClicked: sheet.edit(null)
            }
            ActionButton { text: "关闭"; onClicked: sheet.close() }
        }

        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: !sheet.editing && !sheet.pendingDelete

            ListView {
                id: accounts
                anchors.fill: parent
                clip: true
                spacing: 7
                model: sheet.client.state.accounts
                ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

                delegate: Card {
                    id: account
                    elevated: false
                    required property var modelData
                    width: accounts.width
                    height: 65
                    color: modelData.id === sheet.client.state.selectedId ? Theme.successTint : Theme.panel

                    RowLayout {
                        anchors.fill: parent
                        anchors.margins: 7
                        spacing: 4

                        Button {
                            id: select
                            Layout.fillWidth: true
                            Layout.fillHeight: true
                            padding: 6
                            Accessible.name: account.modelData.label || account.modelData.username
                            onClicked: sheet.errorText = sheet.client.selectAccount(account.modelData.id) ? "" : sheet.client.notice
                            background: Rectangle {
                                color: select.hovered ? Theme.accentTint : "transparent"
                                radius: 8
                                border.width: select.visualFocus ? 2 : 0
                                border.color: Theme.accent
                            }
                            contentItem: ColumnLayout {
                                spacing: 2
                                UiText {
                                    Layout.fillWidth: true
                                    text: account.modelData.label || account.modelData.username
                                    font.bold: true
                                }
                                UiText {
                                    visible: !!account.modelData.label
                                    Layout.fillWidth: true
                                    text: account.modelData.username
                                    color: Theme.muted
                                    font.pixelSize: 11
                                }
                            }
                        }
                        ActionButton { text: "编辑"; onClicked: sheet.edit(account.modelData) }
                        ActionButton {
                            text: "删除"
                            danger: true
                            onClicked: {
                                sheet.errorText = ""
                                sheet.pendingDelete = account.modelData.id
                            }
                        }
                    }
                }
            }

            UiText {
                visible: accounts.count === 0
                anchors.centerIn: parent
                text: "尚未添加账户"
                color: Theme.muted
            }
        }

        ScrollView {
            visible: sheet.editing
            Layout.fillWidth: true
            Layout.fillHeight: true
            contentWidth: availableWidth
            clip: true

            ColumnLayout {
                width: parent.width
                spacing: 7

                UiText { text: "账户备注（可选）"; color: Theme.muted }
                FormField { id: remark; Layout.fillWidth: true; maximumLength: 40; Accessible.name: "账户备注" }
                UiText { text: "校园网账号"; color: Theme.muted }
                FormField { id: user; Layout.fillWidth: true; maximumLength: 128; Accessible.name: "校园网账号" }
                UiText { text: "密码"; color: Theme.muted }
                FormField { id: secret; Layout.fillWidth: true; maximumLength: 256; echoMode: TextInput.Password; Accessible.name: "密码" }
            }
        }

        UiText {
            visible: !!sheet.pendingDelete
            Layout.fillHeight: true
            Layout.fillWidth: true
            text: "确认删除这个账户？"
            font.pixelSize: 16
        }

        UiText {
            Layout.fillWidth: true
            visible: text.length > 0
            text: sheet.errorText || (sheet.editing || sheet.pendingDelete ? ""
                : sheet.previewMode ? "账户信息仅在本次预览中保留。" : "账户保存在本机，密码由 Windows 保护。")
            color: sheet.errorText ? Theme.danger : Theme.muted
            wrapMode: Text.Wrap
            elide: Text.ElideNone
            font.pixelSize: 12
        }

        RowLayout {
            visible: sheet.editing || !!sheet.pendingDelete
            Layout.fillWidth: true
            ActionButton {
                Layout.fillWidth: true
                text: sheet.pendingDelete ? "确认删除" : "保存账户"
                primary: !sheet.pendingDelete
                danger: !!sheet.pendingDelete
                onClicked: {
                    if (sheet.pendingDelete) {
                        if (sheet.client.deleteAccount(sheet.pendingDelete))
                            sheet.pendingDelete = ""
                        else
                            sheet.errorText = sheet.client.notice
                    } else {
                        const saved = sheet.client.saveAccount(sheet.editingId, remark.text, user.text, secret.text)
                        secret.clear()
                        if (saved)
                            sheet.editing = false
                        else
                            sheet.errorText = sheet.client.notice
                    }
                }
            }
            ActionButton {
                text: "取消"
                onClicked: {
                    secret.clear()
                    sheet.errorText = ""
                    sheet.editing = false
                    sheet.pendingDelete = ""
                }
            }
        }
    }
}
