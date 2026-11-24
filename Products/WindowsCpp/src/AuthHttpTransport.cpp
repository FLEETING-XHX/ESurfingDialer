#include "AuthHttpTransport.h"

#include <QEventLoop>
#include <QNetworkReply>
#include <QThread>
#include <QTimer>
#include <algorithm>

using namespace std::chrono_literals;

namespace esurfing::auth2104 {
HttpTransport::HttpTransport(std::stop_token stop) : stop_(stop) {}

Response HttpTransport::post(const Request& request) { return send(request, true); }
Response HttpTransport::get(const Request& request) { return send(request, false); }

Response HttpTransport::send(const Request& request, bool post) {
    if (QThread::currentThread() != manager_.thread()) throw Error("HTTP_WRONG_THREAD");
    if (stop_.stop_requested()) throw Error("HTTP_CANCELLED");
    if (request.responseLimit < 1 || request.responseLimit > 1024 * 1024 ||
        request.timeout <= 0s || request.timeout > 45s)
        throw Error("HTTP_LIMIT_INVALID");
    QNetworkRequest outgoing(request.url);
    outgoing.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);
    outgoing.setAttribute(QNetworkRequest::CookieLoadControlAttribute, QNetworkRequest::Manual);
    outgoing.setAttribute(QNetworkRequest::CookieSaveControlAttribute, QNetworkRequest::Manual);
    outgoing.setAttribute(QNetworkRequest::AuthenticationReuseAttribute, QNetworkRequest::Manual);
    outgoing.setAttribute(QNetworkRequest::CacheLoadControlAttribute, QNetworkRequest::AlwaysNetwork);
    outgoing.setAttribute(QNetworkRequest::CacheSaveControlAttribute, false);
    for (auto it = request.headers.cbegin(); it != request.headers.cend(); ++it)
        outgoing.setRawHeader(it.key(), it.value());

    QEventLoop loop;
    const std::unique_ptr<QNetworkReply> reply(post ? manager_.post(outgoing, request.body) : manager_.get(outgoing));
    reply->setReadBufferSize(std::min<qsizetype>(64 * 1024, request.responseLimit + 1));
    QTimer deadline, cancellation;
    deadline.setSingleShot(true);
    deadline.setTimerType(Qt::PreciseTimer);
    QByteArray body;
    const char* failure = nullptr;
    const auto abort = [&](const char* code) {
        if (!failure) failure = code;
        reply->abort();
        loop.quit();
    };
    const auto read = [&] {
        if (failure || !reply->isOpen() || reply->bytesAvailable() <= 0) return;
        body.append(reply->read(request.responseLimit - body.size() + 1));
        if (body.size() > request.responseLimit) abort("HTTP_BODY_TOO_LARGE");
    };
    QObject::connect(reply.get(), &QNetworkReply::readyRead, &loop, read);
    QObject::connect(reply.get(), &QNetworkReply::finished, &loop, &QEventLoop::quit);
    QObject::connect(&deadline, &QTimer::timeout, &loop, [&] { abort("HTTP_TIMEOUT"); });
    QObject::connect(&cancellation, &QTimer::timeout, &loop, [&] {
        if (stop_.stop_requested()) abort("HTTP_CANCELLED");
    });
    deadline.start(std::chrono::duration_cast<std::chrono::milliseconds>(request.timeout));
    if (stop_.stop_possible()) cancellation.start(50ms);
    if (!reply->isFinished()) loop.exec(QEventLoop::ExcludeUserInputEvents);
    read();
    if (failure) throw Error(failure);
    if (stop_.stop_requested()) throw Error("HTTP_CANCELLED");
    const auto status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    if (!status || (status >= 200 && status < 300 && reply->error() != QNetworkReply::NoError))
        throw Error("HTTP_TRANSPORT_ERROR");
    return {status, std::move(body), reply->attribute(QNetworkRequest::RedirectionTargetAttribute).toUrl()};
}
}
