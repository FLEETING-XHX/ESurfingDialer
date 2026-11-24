#include "AuthHttpTransport.h"
#include "AuthWorker.h"

#include <QCommandLineParser>
#include <QCoreApplication>
#include <QCryptographicHash>
#include <QFileInfo>
#include <QTemporaryFile>
#include <QTextStream>
#include <QUuid>

int main(int argc, char** argv) {
    using namespace esurfing::auth2104;
    QCoreApplication app(argc, argv);
    QCommandLineParser parser;
    parser.setApplicationDescription(QStringLiteral("Capture one 2104 bootstrap module without using an account."));
    parser.addHelpOption();
    const QCommandLineOption output(QStringLiteral("output"), QStringLiteral("Path for the private module file."),
                                    QStringLiteral("file"));
    parser.addOption(output);
    parser.process(app);
    const auto path = parser.value(output);
    if (path.isEmpty() || !parser.positionalArguments().isEmpty()) parser.showHelp(2);

    try {
        const QFileInfo target(path);
        if (target.exists() || target.isSymLink() || target.isJunction()) throw Error("OUTPUT_FILE_EXISTS");
        QTemporaryFile file(target.absoluteFilePath() + QStringLiteral(".XXXXXX"));
        if (!file.open()) throw Error("OUTPUT_WRITE_FAILED");
        const auto portal = productionAuth().discover({});
        HttpTransport http;
        const auto id = QUuid::createUuid().toString(QUuid::WithoutBraces);
        const auto module = fetchBootstrap(portal.ticketUrl, id, http);
        if (file.write(module) != module.size() || !file.flush()) throw Error("OUTPUT_WRITE_FAILED");
        file.close();
        if (!file.rename(target.absoluteFilePath())) throw Error("OUTPUT_WRITE_FAILED");
        file.setAutoRemove(false);
        const auto digest = QCryptographicHash::hash(module, QCryptographicHash::Sha256).toHex();
        QTextStream(stdout) << "module-bytes=" << module.size() << "\nsha256=" << digest << '\n';
        return 0;
    } catch (const Error& error) {
        QTextStream(stderr) << error.what() << '\n';
        return 1;
    } catch (...) {
        QTextStream(stderr) << "BOOTSTRAP_PROBE_FAILED\n";
        return 1;
    }
}
