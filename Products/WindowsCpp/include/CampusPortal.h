#pragma once

#include "AuthSession2104.h"

namespace esurfing::auth2104 {
struct PortalEndpoints {
    QUrl ticketUrl;
    QUrl loginUrl;
    QString userIpv4;
};

std::optional<PortalEndpoints> parsePortal(QByteArrayView page);
PortalEndpoints discoverPortal(const QUrl& probe, const std::function<Response(const Request&)>& fetch,
                               Channel channel = Channel::windows2104);
}
