#include "esurfing/diagnostic.hpp"
#include <atomic>
#include <initializer_list>
#include <utility>
namespace esurfing {
std::uint64_t next_request_attempt() noexcept {
    // Metadata only. Avoid introducing an 8-byte atomic runtime dependency on
    // 32-bit targets; timestamps/process lifetime disambiguate eventual wrap.
    static std::atomic<std::uint32_t> counter=0;
    auto value=counter.fetch_add(1,std::memory_order_relaxed)+1;
    if(!value)value=counter.fetch_add(1,std::memory_order_relaxed)+1;
    return value;
}
const char* operation_name(Operation operation) noexcept {
    switch(operation){
    case Operation::probe:return "probe";case Operation::discovery:return "discovery";
    case Operation::bootstrap:return "bootstrap";case Operation::ticket:return "ticket";
    case Operation::login:return "login";case Operation::keep:return "keep";case Operation::term:return "term";
    default:return "unknown";
    }
}
const char* diagnostic_code(std::string_view code) noexcept {
    // Exact literals only: never log arbitrary exception strings, even uppercase ones.
    for(const auto* known:{"HTTP_DNS_FAILED","HTTP_PROXY_DNS_FAILED","HTTP_CONNECT_FAILED","HTTP_TIMEOUT",
        "HTTP_TLS_HANDSHAKE_FAILED","HTTP_TLS_VERIFY_FAILED","HTTP_TLS_CONFIG_INVALID","HTTP_SEND_FAILED",
        "HTTP_RECEIVE_FAILED","HTTP_EMPTY_REPLY","HTTP_TRANSPORT_ERROR","HTTP_STATUS_ERROR","HTTP_UNEXPECTED_RESPONSE","HTTP_BODY_TOO_LARGE","HTTP_HEADERS_INVALID",
        "HTTP_POLICY_INVALID","HTTP_HEADER_INVALID","CURL_INIT_FAILED","CURL_OPTION_FAILED","HTTP_HEADERS_FAILED",
        "AUTH_HTTP_ERROR","AUTH_BODY_EMPTY","AUTH_BODY_TOO_LARGE","TERM_HTTP_ERROR","PORTAL_HTTP_ERROR",
        "CIPHERTEXT_SIZE_INVALID","CIPHERTEXT_HEX_INVALID","CIPHERTEXT_PADDING_INVALID","PLAINTEXT_NUL_INVALID",
        "PLAINTEXT_SIZE_INVALID","DECRYPT_FAILED","HEARTBEAT_PROTOCOL_INVALID","XML_INVALID","XML_INPUT_INVALID",
        "XML_FIELD_INVALID","XML_FIELDS_INVALID","XML_DEPTH_INVALID","XML_ROOT_INVALID","XML_PARSE_FAILED",
        "XML_TOO_LARGE","INTERVAL_INVALID","INTERVAL_POLICY_INVALID","REQUEST_XML_INVALID","REQUEST_TIME_INVALID","TICKET_INVALID","LOGIN_REJECTED",
        "LOGIN_RESPONSE_INVALID","KEEP_RESPONSE_INVALID","AUTH_GENERATION_CANCELLED","NETWORK_CANDIDATE_CHANGED",
        "CANCELLED","MODULE_ENCODING_PROFILE_UNKNOWN","MODULE_CODE_PROFILE_UNKNOWN","MODULE_IMAGE_PROFILE_UNKNOWN",
        "MODULE_FORMAT_UNKNOWN","MODULE_HEADER_TRUNCATED","MODULE_BODY_TRUNCATED","NATIVE_LIBRARY_PROFILE_UNKNOWN",
        "ALGORITHM_UNSUPPORTED","CIPHER_INIT_FAILED","ZSM_HEADER_INVALID","ZSM_ALGORITHM_UNSUPPORTED",
        "BOOTSTRAP_SIZE_INVALID","PORTAL_REDIRECT_LIMIT","PORTAL_REDIRECT_INVALID","PORTAL_IP_INVALID",
        "SESSION_IP_INVALID","URL_INVALID","URL_FORBIDDEN","PORTAL_XML_INVALID","PORTAL_BODY_TOO_LARGE",
        "PORTAL_CONFIG_DUPLICATE","PORTAL_CONFIG_TRUNCATED","PORTAL_FIELDS_INVALID","PORTAL_AUTH_CONFLICT","UNKNOWN_FAILURE"})if(code==known)return known;
    return "UNKNOWN_FAILURE";
}
const char* diagnostic_domain(std::string_view raw) noexcept {
    const std::string_view code=diagnostic_code(raw);
    if(code.starts_with("MODULE_")||code.starts_with("NATIVE_LIBRARY_")||code.starts_with("ALGORITHM_")||code.starts_with("CIPHER")||code.starts_with("CIPHERTEXT_")||code.starts_with("PLAINTEXT_")||code=="DECRYPT_FAILED"||code.starts_with("ZSM_"))return "模块或算法兼容性";
    if(code.starts_with("HTTP_")||code.ends_with("HTTP_ERROR"))return "网络请求或HTTP响应";
    if(code.starts_with("CURL_"))return "核心网络运行库";
    if(code=="UNKNOWN_FAILURE")return "未知（需结合宿主机记录）";
    return "认证协议或会话";
}
const char* diagnostic_reason(std::string_view code) noexcept {
    const std::pair<std::string_view,const char*> reasons[]={
        {"HTTP_DNS_FAILED","域名解析失败"},{"HTTP_PROXY_DNS_FAILED","代理域名解析失败"},
        {"HTTP_CONNECT_FAILED","连接目标失败"},{"HTTP_TIMEOUT","请求超时"},
        {"HTTP_TLS_HANDSHAKE_FAILED","安全连接握手失败"},{"HTTP_TLS_VERIFY_FAILED","服务端证书校验失败"},{"HTTP_TLS_CONFIG_INVALID","安全连接配置异常"},
        {"HTTP_SEND_ERROR","请求发送失败"},{"HTTP_SEND_FAILED","请求发送失败"},{"HTTP_RECEIVE_FAILED","响应接收失败"},
        {"HTTP_EMPTY_REPLY","对端未返回有效响应"},{"HTTP_TRANSPORT_ERROR","网络传输异常"},
        {"AUTH_HTTP_ERROR","认证端点返回非成功HTTP状态"},{"TERM_HTTP_ERROR","下线端点返回非成功HTTP状态"},
        {"HEARTBEAT_TRANSPORT_ERROR","心跳传输异常，具体原因见操作失败记录"},{"HEARTBEAT_PROTOCOL_INVALID","心跳响应不符合协议"},
        {"LOGIN_REJECTED","认证请求被拒绝"},{"MODULE_ENCODING_PROFILE_UNKNOWN","认证模块编码不受支持"},
        {"MODULE_CODE_PROFILE_UNKNOWN","认证模块代码特征不受支持"},{"MODULE_IMAGE_PROFILE_UNKNOWN","认证模块镜像特征不受支持"},
        {"NATIVE_LIBRARY_PROFILE_UNKNOWN","本地模块加载器特征不匹配"},{"ALGORITHM_UNSUPPORTED","认证算法不受支持"},
        {"CIPHERTEXT_PADDING_INVALID","解密结果填充不合法，需检查响应与算法兼容性"},{"CIPHERTEXT_HEX_INVALID","加密响应包含非法字符"},
        {"DECRYPT_FAILED","响应解密失败"},{"CIPHER_INIT_FAILED","算法初始化失败"},{"NETWORK_CANDIDATE_CHANGED","检测到认证网络地址发生变化"},
        {"STATE_DIRECTORY_OWNED","状态目录已被另一个进程使用"},{"DEVICE_IDENTITY_INVALID","设备身份文件无效"},
        {"LOG_SINK_FAILED","日志输出失败"},{"LOG_IO_FAILED","日志文件读写失败"},{"RESTART_WINDOW_INVALID","每日重启时间窗口无效"},
        {"STATE_SYNC_FAILED","关键状态同步到存储失败"},{"URL_FORBIDDEN","禁止连接回环或链路本地地址"},
        {"CREDENTIALS_REQUIRED","缺少账户或密码配置"},{"NATIVE_STARTUP_FAILED","核心启动失败"}};
    for(auto [value,label]:reasons)if(code==value)return label;
    return diagnostic_domain(code);
}
}
