import QtQuick

Item {
    id: icon
    property string name: "PenAccountIcon"
    property string variant: ""
    implicitWidth: 20
    implicitHeight: 20
    width: implicitWidth
    height: implicitHeight

    Image {
        anchors.fill: parent
        source: "qrc:/assets/icons/" + icon.name + (icon.variant ? "-" + icon.variant : "") + ".svg"
        sourceSize.width: Math.ceil(icon.width * Screen.devicePixelRatio)
        sourceSize.height: Math.ceil(icon.height * Screen.devicePixelRatio)
        fillMode: Image.PreserveAspectFit
    }
}
