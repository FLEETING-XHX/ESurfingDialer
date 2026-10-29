package com.rsplwe.esurfing

import org.apache.commons.cli.DefaultParser
import java.io.File

/** 解析生产入口捕获的实际 argv，替身凭据只来自隔离测试。 */
object CommandLineRegression {
    @JvmStatic fun main(args: Array<String>) {
        var checks = 0
        val cases = File(args.single()).readLines().filter { it.isNotBlank() }.map(StateJson::decode)
        check(cases.size == 16) { "Expected both launch branches and backends" }; checks++
        for (case in cases) {
            val argv = Array((case.getValue("argvCount") as Long).toInt()) { case.getValue("argv$it") as String }
            val parsed = DefaultParser().parse(DialerApp.commandLineOptions(), argv)
            check(parsed.getOptionValue("user") == case["user"]) { "CLI changed fixture user" }; checks++
            check(parsed.getOptionValue("password") == case["password"]) { "CLI changed fixture password" }; checks++
            check(parsed.hasOption("dynarmic") == case["dynarmic"]) { "Password changed backend selection" }; checks++
        }
        println("Docker command line regression: $checks checks passed")
    }
}
