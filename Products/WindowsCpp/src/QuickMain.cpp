#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQuickStyle>
#include <QQuickWindow>
#include <QFontDatabase>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QTimer>
#include <memory>
#include <windows.h>
#include <dwmapi.h>
#include "ClientBridge.h"
#include "LocalClientServices.h"
#include "Native2082Cipher.h"
int main(int argc,char** argv){
    QGuiApplication app(argc,argv);
    QCoreApplication::setOrganizationName("ESurfingDialer");
    QCoreApplication::setApplicationName("ESurfingDialerWindowsCpp");
    QQuickStyle::setStyle("Basic");
    const auto regularFont=QFontDatabase::addApplicationFont(":/fonts/NotoSansSC-Regular.ttf");
    const auto boldFont=QFontDatabase::addApplicationFont(":/fonts/NotoSansSC-Bold.ttf");
    app.setFont(QFont("Noto Sans SC"));
    const bool smokeTest=app.arguments().contains("--smoke-test");
    if(smokeTest && (regularFont < 0 || boldFont < 0))return 5;
    const bool preview=app.arguments().contains("--preview");
    std::unique_ptr<QTemporaryDir> smokeDirectory;
    std::unique_ptr<esurfing::IClientServices> service;
    if(preview) {
        service=std::make_unique<esurfing::PreviewServices>();
    } else {
        auto directory=QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation);
        if(smokeTest){
            smokeDirectory=std::make_unique<QTemporaryDir>();
            if(!smokeDirectory->isValid())return 3;
            directory=smokeDirectory->path();
        }
        service=std::make_unique<esurfing::LocalClientServices>(
            directory,
            esurfing::auth2104::productionAuth(esurfing::auth2104::native2082CipherFactory(),
                                              esurfing::auth2104::Channel::android2082));
        if(smokeTest && service->snapshot().phase != esurfing::ConnectionPhase::offline)return 4;
    }
    esurfing::ClientBridge bridge(*service);
    QQmlApplicationEngine engine;
    auto warnings=std::make_shared<int>(0);
    QObject::connect(&engine,&QQmlApplicationEngine::warnings,&app,[warnings](const QList<QQmlError>& errors){*warnings+=static_cast<int>(errors.size());});
    QObject::connect(&engine,&QQmlApplicationEngine::objectCreationFailed,&app,[]{QCoreApplication::exit(1);},Qt::QueuedConnection);
    BOOL animateControls = TRUE;
    SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, &animateControls, 0);
    engine.setInitialProperties({{"client",QVariant::fromValue(&bridge)},
                                 {"previewMode",preview},
                                 {"animateControls",animateControls != FALSE}});
    engine.loadFromModule("ESurfing","Main");
    if(engine.rootObjects().isEmpty())return 1;
    // Let Windows decorate the native surface; all page rendering remains in Qt Quick.
    if(auto* window=qobject_cast<QQuickWindow*>(engine.rootObjects().first())) {
        const DWORD rounded=2;
        DwmSetWindowAttribute(reinterpret_cast<HWND>(window->winId()),33,&rounded,sizeof(rounded));
    }
    if(smokeTest){
        auto count=std::make_shared<int>(0);
        QObject::connect(&bridge,&esurfing::ClientBridge::stateChanged,&app,[count]{++*count;});
        QTimer::singleShot(1250,&app,[count,warnings]{QCoreApplication::exit(*count==0 && *warnings==0 ? 0 : 2);});
    }
    return app.exec();
}
