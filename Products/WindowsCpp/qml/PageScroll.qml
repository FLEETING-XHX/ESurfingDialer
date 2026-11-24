import QtQuick
import QtQuick.Controls.Basic

Flickable {
    id: scroll
    required property real overlayInset
    default property alias body: bodyColumn.data
    readonly property real bodyWidth: bodyColumn.width
    clip: true
    contentWidth: width
    contentHeight: bodyColumn.implicitHeight + bodyColumn.y + overlayInset
    boundsBehavior: Flickable.StopAtBounds
    onVisibleChanged: {
        if (visible && Theme.motionEnabled && reveal)
            reveal.restart()
    }

    Column {
        id: bodyColumn
        x: Theme.pageInset
        y: 4
        width: Math.max(0, scroll.width - 2 * Theme.pageInset)
        spacing: 16
    }

    NumberAnimation {
        id: reveal
        target: bodyColumn
        property: "opacity"
        from: 0.72
        to: 1
        duration: Theme.changeDuration
        easing.type: Easing.OutCubic
    }

    ScrollBar.vertical: ScrollBar {
        height: Math.max(0, scroll.height - scroll.overlayInset)
        width: 6
        policy: ScrollBar.AsNeeded
    }

    Connections {
        target: scroll.Window.window
        enabled: scroll.visible
        function onActiveFocusItemChanged() {
            const item = scroll.Window.window.activeFocusItem
            if (!item)
                return
            for (let ancestor = item; ancestor; ancestor = ancestor.parent) {
                if (ancestor !== bodyColumn)
                    continue
                const top = item.mapToItem(scroll.contentItem, 0, 0).y
                const bottom = top + item.height
                const available = scroll.height - scroll.overlayInset
                let next = scroll.contentY
                if (top < next + 6)
                    next = top - 6
                else if (bottom > next + available)
                    next = bottom - available
                if (next !== scroll.contentY)
                    scroll.contentY = Math.max(0, Math.min(next, scroll.contentHeight - scroll.height))
                break
            }
        }
    }
}
