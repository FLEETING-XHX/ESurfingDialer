#include "CampusPortal.h"

#include <QRegularExpression>
#include <QSet>
#include <QStringDecoder>
#include <QUrlQuery>
#include <QXmlStreamReader>

using namespace std::chrono_literals;

namespace esurfing::auth2104 {
namespace {
constexpr qsizetype maxPage = 128 * 1024;
constexpr QByteArrayView start = "<!--//config.campus.js.chinatelecom.com";
constexpr QByteArrayView end = "//config.campus.js.chinatelecom.com-->";

// Preserve quoted markup, comments and CDATA while repairing bare query delimiters.
QString escapeQuerySeparators(QStringView xml) {
    static const QRegularExpression separator(R"(&(?=[a-zA-Z0-9_.~-]+=))");
    QString result;
    QChar quote;
    bool tag = false;
    for (qsizetype i = 0; i < xml.size();) {
        const auto rest = xml.sliced(i);
        QStringView close;
        if (quote.isNull() && rest.startsWith(u"<![CDATA[")) close = u"]]>";
        if (quote.isNull() && rest.startsWith(u"<!--")) close = u"-->";
        if (quote.isNull() && rest.startsWith(u"<?")) close = u"?>";
        if (!close.isEmpty()) {
            const auto position = rest.indexOf(close);
            if (position < 0) throw Error("PORTAL_XML_INVALID");
            const auto length = position + close.size();
            result += rest.first(length);
            i += length;
            continue;
        }
        const auto ch = xml.at(i);
        if (ch == u'<' && quote.isNull()) tag = true;
        if (tag && (ch == u'\'' || ch == u'"')) {
            if (quote.isNull()) quote = ch;
            else if (quote == ch) quote = {};
        }
        if (ch == u'>' && quote.isNull()) tag = false;
        if (ch == u'&' && (!tag || !quote.isNull()) &&
            separator.matchView(xml, i, QRegularExpression::NormalMatch,
                                QRegularExpression::AnchorAtOffsetMatchOption).hasMatch())
            result += u"&amp;";
        else
            result += ch;
        ++i;
    }
    return result;
}

QUrl endpoint(const QString& value, const QString& path) {
    QUrl url(value, QUrl::StrictMode);
    if (!allowedUrl(url) || !url.path(QUrl::FullyEncoded).endsWith(path))
        throw Error("PORTAL_ENDPOINT_INVALID");
    if ((url.scheme() == "http" && url.port() == 80) || (url.scheme() == "https" && url.port() == 443))
        url.setPort(-1);
    return url;
}
}

std::optional<PortalEndpoints> parsePortal(QByteArrayView page) {
    if (page.size() > maxPage) throw Error("PORTAL_BODY_TOO_LARGE");
    const auto first = page.indexOf(start);
    if (first < 0) return std::nullopt;
    const auto offset = first + start.size();
    if (page.indexOf(start, offset) >= 0) throw Error("PORTAL_CONFIG_DUPLICATE");
    const auto last = page.indexOf(end, offset);
    if (last < 0) throw Error("PORTAL_CONFIG_TRUNCATED");
    QStringDecoder decoder(QStringDecoder::Utf8);
    QString fragment = decoder(page.sliced(offset, last - offset));
    if (decoder.hasError()) throw Error("PORTAL_ENCODING_UNSUPPORTED");
    fragment = fragment.trimmed();
    if (fragment.startsWith(QChar::ByteOrderMark)) fragment = fragment.sliced(1).trimmed();
    if (fragment.startsWith(u"<?xml ")) {
        const auto close = fragment.indexOf(u"?>");
        if (close < 0) throw Error("PORTAL_XML_INVALID");
        fragment = fragment.sliced(close + 2);
    }
    const auto xml = u"<portal-config>" + escapeQuerySeparators(fragment) + u"</portal-config>";
    QXmlStreamReader reader(xml);
    QSet<QUrl> logins;
    QUrl ticket;
    int depth = 0;
    while (!reader.atEnd()) {
        const auto token = reader.readNext();
        if (token == QXmlStreamReader::DTD || token == QXmlStreamReader::EntityReference)
            throw Error("PORTAL_XML_INVALID");
        if (token == QXmlStreamReader::EndElement) --depth;
        if (token != QXmlStreamReader::StartElement) continue;
        if (++depth > 64) throw Error("PORTAL_XML_INVALID");
        const auto name = reader.name().toString();
        if (name != u"auth-url" && name != u"ticket-url") continue;
        if (!reader.namespaceUri().isEmpty()) throw Error("PORTAL_FIELDS_INVALID");
        const auto value = reader.readElementText().trimmed();
        if (reader.hasError() || value.isEmpty()) throw Error("PORTAL_FIELDS_INVALID");
        --depth;
        if (name == u"auth-url") {
            logins.insert(endpoint(value, QStringLiteral("/auth.cgi")));
        } else {
            if (!ticket.isEmpty()) throw Error("PORTAL_FIELDS_INVALID");
            ticket = endpoint(value, QStringLiteral("/ticket.cgi"));
        }
    }
    if (reader.hasError() || depth != 0 || logins.size() != 1 || ticket.isEmpty())
        throw Error("PORTAL_FIELDS_INVALID");
    const QUrlQuery query(ticket);
    const auto users = query.allQueryItemValues("wlanuserip", QUrl::FullyDecoded);
    const auto accessPoints = query.allQueryItemValues("wlanacip", QUrl::FullyDecoded);
    if (users.size() != 1 || accessPoints.size() != 1 ||
        !validIpv4(users.first()) || !validIpv4(accessPoints.first()))
        throw Error("PORTAL_IP_INVALID");
    return PortalEndpoints{ticket, *logins.cbegin(), users.first()};
}

PortalEndpoints discoverPortal(const QUrl& probe, const std::function<Response(const Request&)>& fetch, Channel channel) {
    auto url = probe;
    QSet<QUrl> visited;
    for (int hop = 0; hop < 4; ++hop) {
        if (!allowedUrl(url) || visited.contains(url)) throw Error("PORTAL_REDIRECT_INVALID");
        visited.insert(url);
        const auto response = fetch(Request{url, {{"User-Agent", agentFor(channel)}}, {}, maxPage, 10s});
        if (response.status == 301 || response.status == 302 || response.status == 303 ||
            response.status == 307 || response.status == 308) {
            if (response.redirect.isEmpty()) throw Error("PORTAL_REDIRECT_INVALID");
            url = url.resolved(response.redirect);
            continue;
        }
        if (response.status == 204) throw Error("PORTAL_NETWORK_ALREADY_ONLINE");
        if (response.status < 200 || response.status >= 300) throw Error("PORTAL_HTTP_ERROR");
        if (const auto portal = parsePortal(response.body)) return *portal;
        throw Error("PORTAL_CONFIG_MISSING");
    }
    throw Error("PORTAL_REDIRECT_LIMIT");
}
}
