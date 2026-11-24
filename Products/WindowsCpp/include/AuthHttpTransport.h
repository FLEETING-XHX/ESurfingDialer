#pragma once

#include "AuthSession2104.h"
#include <QNetworkAccessManager>
#include <stop_token>

namespace esurfing::auth2104 {
// Create and use on the same Qt worker thread. post() runs a local event loop.
class HttpTransport final : public Transport {
public:
    explicit HttpTransport(std::stop_token stop = {});
    Response post(const Request& request) override;
    Response get(const Request& request);
private:
    Response send(const Request& request, bool post);
    QNetworkAccessManager manager_;
    std::stop_token stop_;
};
}
