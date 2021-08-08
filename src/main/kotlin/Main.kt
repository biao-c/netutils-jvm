import picocli.CommandLine
import picocli.CommandLine.Command
import kotlin.system.exitProcess

@Command(
    name = "NetUtils",
    mixinStandardHelpOptions = true,
    version = ["1.1"],
    subcommands = [
        YouTube::class,
        GoogleDrive::class,
        OneDrive::class,
        CloudLucky::class
    ],
    description = ["Network utilities."]
)
class NetUtils

fun main(args: Array<String>): Unit = exitProcess(CommandLine(NetUtils()).execute(*args))