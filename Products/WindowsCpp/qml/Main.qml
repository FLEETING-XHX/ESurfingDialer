import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

ApplicationWindow {
    id: window
    required property var client
    required property bool previewMode
    required property bool animateControls
    Component.onCompleted: Theme.motionEnabled = animateControls
    property int page: 0
    readonly property var pages: [
        {title: "天翼校园", label: "首页", icon: "PenHouseIcon"},
        {title: "日志", label: "日志", icon: "PenLogIcon"},
        {title: "设置", label: "设置", icon: "PenSettingsIcon"}
    ]
    visible: true
    width: Math.min(442, Screen.desktopAvailableWidth * 0.9)
    height: Math.min(736, Screen.desktopAvailableHeight * 0.9)
    minimumWidth: 328
    minimumHeight: 512
    title: "天翼校园"
    color: Theme.surface
    flags: Qt.Window | Qt.FramelessWindowHint | Qt.WindowMinimizeButtonHint
    x: (Screen.width - width) / 2
    y: (Screen.height - height) / 2

    Item {
        id: shell
        objectName: "pageShell"
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.horizontalCenter: parent.horizontalCenter
        width: Math.min(window.width, 600)
        readonly property real overlayInset: nav.height + nav.anchors.bottomMargin + 27

        Item {
            id: header
            anchors.left: parent.left
            anchors.right: parent.right
            height: 88

            RowLayout {
                anchors.verticalCenter: parent.verticalCenter
                anchors.left: parent.left
                anchors.leftMargin: Theme.pageInset
                spacing: 10

                UiText { text: window.pages[window.page].title; font.pixelSize: 26; font.bold: true }
                UiText {
                    visible: window.previewMode
                    text: "界面预览"
                    color: Theme.muted
                    font.pixelSize: 12
                }
            }

            Item {
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                anchors.left: parent.left
                anchors.right: windowActions.left
                MouseArea { anchors.fill: parent; onPressed: window.startSystemMove() }
            }

            Row {
                id: windowActions
                anchors.right: parent.right
                anchors.rightMargin: Theme.pageInset
                anchors.verticalCenter: parent.verticalCenter
                spacing: 5
                ActionButton {
                    width: 38; height: 40; text: "−"
                    Accessible.name: "最小化窗口"
                    onClicked: window.showMinimized()
                }
                ActionButton {
                    width: 38; height: 40; text: "×"
                    Accessible.name: "退出客户端"
                    onClicked: window.close()
                }
            }
        }

        Item {
            id: viewport
            anchors.top: header.bottom
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom

            HomePage {
                anchors.fill: parent
                client: window.client
                previewMode: window.previewMode
                visible: window.page === 0
                overlayInset: shell.overlayInset
                onManageAccounts: addNew => {
                    if (addNew)
                        accountSheet.openNew()
                    else
                        accountSheet.open()
                }
            }
            LogPage {
                anchors.fill: parent
                client: window.client
                visible: window.page === 1
                overlayInset: shell.overlayInset
            }
            SettingsPage {
                anchors.fill: parent
                client: window.client
                visible: window.page === 2
                overlayInset: shell.overlayInset
                onShowDetails: details.open()
            }

            Rectangle {
                objectName: "bottomFade"
                z: 1
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                height: shell.overlayInset
                gradient: Gradient {
                    GradientStop { position: 0; color: Qt.rgba(Theme.surface.r, Theme.surface.g, Theme.surface.b, 0) }
                    GradientStop { position: 0.52; color: Qt.rgba(Theme.surface.r, Theme.surface.g, Theme.surface.b, 0.65) }
                    GradientStop { position: 1; color: Theme.surface }
                }
            }
        }

        Rectangle {
            id: notice
            visible: false
            z: 2
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.leftMargin: Theme.pageInset
            anchors.rightMargin: Theme.pageInset
            anchors.bottom: nav.top
            anchors.bottomMargin: 14
            height: noticeText.implicitHeight + 22
            radius: 12
            color: Theme.accentTint
            border.color: Theme.accent

            UiText {
                id: noticeText
                anchors.top: parent.top
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.margins: 11
                text: window.client.notice
                wrapMode: Text.Wrap
                elide: Text.ElideNone
                color: Theme.accentInk
                font.pixelSize: 12
            }
            Timer { id: noticeTimer; interval: 3800; onTriggered: notice.visible = false }
            Connections {
                target: window.client
                function onNoticeChanged() {
                    if (!accountSheet.opened) {
                        notice.visible = true
                        noticeTimer.restart()
                    }
                }
            }
        }

        Card {
            id: nav
            objectName: "floatingNavigation"
            z: 10
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottom: parent.bottom
            anchors.bottomMargin: 16
            width: Math.min(330, parent.width - 48)
            height: 60
            radius: 21
            color: Theme.nav
            border.width: 1
            border.color: Theme.navBorder
            enabled: !accountSheet.opened && !details.opened
            readonly property int tabInset: 6
            readonly property int tabGap: 4
            readonly property real tabWidth: (width - 2 * tabInset - (window.pages.length - 1) * tabGap) / window.pages.length

            Rectangle {
                x: nav.tabInset + window.page * (nav.tabWidth + nav.tabGap)
                y: nav.tabInset
                width: nav.tabWidth
                height: nav.height - 2 * nav.tabInset
                radius: 16
                color: Theme.accentTint
                Behavior on x {
                    NumberAnimation { duration: Theme.changeDuration; easing.type: Easing.OutCubic }
                }
            }

            Row {
                anchors.fill: parent
                anchors.margins: nav.tabInset
                spacing: nav.tabGap

                Repeater {
                    model: window.pages
                    delegate: Button {
                        id: tab
                        required property var modelData
                        required property int index
                        readonly property bool selected: window.page === index
                        width: nav.tabWidth
                        height: nav.height - 2 * nav.tabInset
                        Accessible.checkable: true
                        Accessible.checked: selected
                        Accessible.name: modelData.label
                        onClicked: {
                            notice.visible = false
                            window.page = index
                        }
                        background: Rectangle {
                            radius: 16
                            color: !tab.selected && (tab.hovered || tab.down) ? Theme.hoverSurface : "transparent"
                            Behavior on color { ColorAnimation { duration: Theme.pressDuration } }
                            border.width: tab.visualFocus ? 2 : 0
                            border.color: Theme.accentInk
                        }
                        contentItem: Item {
                            Row {
                                anchors.centerIn: parent
                                spacing: 5
                                scale: Theme.motionEnabled && tab.down ? 0.96 : 1
                                Behavior on scale { NumberAnimation { duration: Theme.pressDuration; easing.type: Easing.OutCubic } }
                                Icon {
                                    name: tab.modelData.icon
                                    variant: tab.selected ? "" : "accent"
                                    width: 17
                                    height: 17
                                    anchors.verticalCenter: parent.verticalCenter
                                }
                                UiText {
                                    text: tab.modelData.label
                                    color: tab.selected ? Theme.accentInk : Theme.muted
                                    font.pixelSize: 13
                                    font.bold: tab.selected
                                    anchors.verticalCenter: parent.verticalCenter
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    AccountSheet {
        id: accountSheet
        parent: Overlay.overlay
        client: window.client
        previewMode: window.previewMode
    }

    Popup {
        id: details
        parent: Overlay.overlay
        anchors.centerIn: parent
        width: Math.min(500, window.width - 28)
        height: Math.min(555, window.height - 44)
        modal: true
        focus: true
        padding: 18
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

        contentItem: ColumnLayout {
            spacing: 14
            UiText { text: "详细日志"; font.pixelSize: 22; font.bold: true }
            ScrollView {
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true
                TextArea {
                    text: window.client.state.status + "\n\n" + window.client.state.logs.join("\n")
                    readOnly: true
                    selectByMouse: true
                    wrapMode: TextEdit.Wrap
                    color: Theme.ink
                    font.family: "Noto Sans SC"
                    font.pixelSize: 13
                    Accessible.name: "详细运行日志"
                }
            }
            ActionButton {
                Layout.fillWidth: true
                text: "关闭详细日志"
                onClicked: details.close()
            }
        }
    }

    Repeater {
        model: [Qt.LeftEdge|Qt.TopEdge,Qt.RightEdge|Qt.TopEdge,Qt.LeftEdge|Qt.BottomEdge,Qt.RightEdge|Qt.BottomEdge,Qt.LeftEdge,Qt.RightEdge,Qt.TopEdge,Qt.BottomEdge]
        delegate: MouseArea {
            required property int modelData
            property bool atLeft: modelData & Qt.LeftEdge
            property bool atRight: modelData & Qt.RightEdge
            property bool atTop: modelData & Qt.TopEdge
            property bool atBottom: modelData & Qt.BottomEdge
            z: (atLeft || atRight) && (atTop || atBottom) ? 2 : 1
            enabled: !accountSheet.opened && !details.opened
            x: atRight ? window.width - 5 : 0
            y: atBottom ? window.height - 5 : 0
            width: atLeft || atRight ? 5 : window.width
            height: atTop || atBottom ? 5 : window.height
            cursorShape: (atLeft || atRight) && (atTop || atBottom)
                ? ((atLeft && atTop) || (atRight && atBottom) ? Qt.SizeFDiagCursor : Qt.SizeBDiagCursor)
                : atLeft || atRight ? Qt.SizeHorCursor : Qt.SizeVerCursor
            onPressed: window.startSystemResize(modelData)
        }
    }
}
