package teksturepako.pakku.api.actions.remote

import teksturepako.pakku.api.actions.errors.ActionError

data class CouldNotInstallRemote(val url: String? = null): ActionError()
{
    override val rawMessage = message(
        "Could not install or update remote", optionalArg(url),
        "\nA remote modpack can only be installed in directory with non-initialized Pakku dev environment.",
    )
}
