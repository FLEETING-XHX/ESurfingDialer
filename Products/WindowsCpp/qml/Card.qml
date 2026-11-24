import QtQuick
Rectangle {
    id: card
    property bool elevated: true
    color: Theme.panel
    radius: 16
    border.width: 1
    border.color: Theme.border

    Rectangle {
        visible: card.elevated
        z: -2
        x: -2
        y: 3
        width: card.width + 4
        height: card.height + 3
        radius: card.radius + 2
        color: Theme.shadowFar
    }
    Rectangle {
        visible: card.elevated
        z: -1
        y: 2
        width: card.width
        height: card.height + 1
        radius: card.radius
        color: Theme.shadowNear
    }
}
