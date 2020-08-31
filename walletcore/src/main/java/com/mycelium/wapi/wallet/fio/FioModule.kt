package com.mycelium.wapi.wallet.fio

import com.mrd.bitlib.model.NetworkParameters
import com.mrd.bitlib.model.hdpath.HdKeyPath
import com.mycelium.generated.wallet.database.WalletDB
import com.mycelium.wapi.wallet.*
import com.mycelium.wapi.wallet.coins.Balance
import com.mycelium.wapi.wallet.fio.coins.FIOMain
import com.mycelium.wapi.wallet.fio.coins.FIOTest
import com.mycelium.wapi.wallet.genericdb.Backing
import com.mycelium.wapi.wallet.manager.Config
import com.mycelium.wapi.wallet.manager.WalletModule
import com.mycelium.wapi.wallet.metadata.IMetaDataStorage
import fiofoundation.io.fiosdk.FIOSDK
import fiofoundation.io.fiosdk.interfaces.ISerializationProvider
import java.util.*

class FioModule(
        private val serializationProvider : ISerializationProvider,
        private val secureStore: SecureKeyValueStore,
        private val backing: Backing<FioAccountContext>,
        private val walletDB: WalletDB,
        networkParameters: NetworkParameters,
        metaDataStorage: IMetaDataStorage,
        private val fioKeyManager: FioKeyManager,
        private val accountListener: AccountListener?
) : WalletModule(metaDataStorage) {

    private val coinType = if (networkParameters.isProdnet) FIOMain else FIOTest
    private val accounts = mutableMapOf<UUID, FioAccount>()
    override val id = ID

    init {
        assetsList.add(coinType)
    }

    private fun getFioSdk(accountIndex: Int): FIOSDK {
        val fioPublicKey = fioKeyManager.getFioPublicKey(accountIndex)
        val publicKey = fioKeyManager.formatPubKey(fioPublicKey)
        // in FIO WIF testnet private keys aren't used
        val privateKey = fioKeyManager.getFioPrivateKey(accountIndex).getBase58EncodedPrivateKey(NetworkParameters.productionNetwork)
        return FIOSDK.getInstance(privateKey, publicKey, serializationProvider, coinType.url)
    }

    override fun loadAccounts(): Map<UUID, WalletAccount<*>> =
            backing.loadAccountContexts()
                    .associateBy({ it.uuid }, { accountFromUUID(it.uuid) })

    private fun accountFromUUID(uuid: UUID): WalletAccount<*> {
        return if (secureStore.getPlaintextValue(uuid.toString().toByteArray()) != null) {
            val fioAddress = FioAddress(coinType, FioAddressData(String(secureStore.getPlaintextValue(uuid.toString().toByteArray()))))
            val accountContext = createAccountContext(uuid)
            val fioAccountBacking = FioAccountBacking(walletDB, accountContext.uuid, coinType)
            val account = FioAccount(accountContext = accountContext, backing = fioAccountBacking,
                    accountListener = accountListener, address = fioAddress)
            accounts[account.id] = account
            account
        } else {
            val accountContext = createAccountContext(uuid)
            val fioAccountBacking = FioAccountBacking(walletDB, accountContext.uuid, coinType)
            val account = FioAccount(accountContext, fioAccountBacking, accountListener,
                    getFioSdk(accountContext.accountIndex))
            accounts[account.id] = account
            account
        }
    }

    override fun createAccount(config: Config): WalletAccount<*> {
        val result: WalletAccount<*>
        val baseLabel: String
        when (config) {
            is FIOMasterseedConfig -> {
                val newIndex = getCurrentBip44Index() + 1
                val accountContext = createAccountContext(fioKeyManager.getUUID(newIndex))
                baseLabel = accountContext.accountName
                backing.createAccountContext(accountContext)
                val fioAccountBacking = FioAccountBacking(walletDB, accountContext.uuid, coinType)
                result = FioAccount(accountContext, fioAccountBacking, accountListener, getFioSdk(newIndex))

            }
            is FIOAddressConfig -> {
                val uuid = UUID.nameUUIDFromBytes(config.address.getBytes())
                secureStore.storePlaintextValue(uuid.toString().toByteArray(),
                        config.address.toString().toByteArray())
                val accountContext = createAccountContext(uuid, isReadOnly = true)
                baseLabel = accountContext.accountName
                backing.createAccountContext(accountContext)
                val fioAccountBacking = FioAccountBacking(walletDB, accountContext.uuid, coinType)
                result = FioAccount(accountContext = accountContext, backing = fioAccountBacking,
                        accountListener = accountListener, address = config.address)
            }
            else -> {
                throw NotImplementedError("Unknown config")
            }
        }
        accounts[result.id] = result
        result.label = createLabel(baseLabel)
        storeLabel(result.id, result.label)
        return result
    }

    private fun getCurrentBip44Index() = accounts.values
            .filter { it.isDerivedFromInternalMasterseed }
            .maxBy { it.accountIndex }
            ?.accountIndex
            ?: -1

    override fun canCreateAccount(config: Config): Boolean {
        return config is FIOMasterseedConfig || config is FIOAddressConfig
    }

    override fun deleteAccount(walletAccount: WalletAccount<*>, keyCipher: KeyCipher): Boolean {
        return if (walletAccount is FioAccount) {
            if (secureStore.getPlaintextValue(walletAccount.id.toString().toByteArray()) != null) {
                secureStore.deletePlaintextValue(walletAccount.id.toString().toByteArray())
            }
            backing.deleteAccountContext(walletAccount.id)
            accounts.remove(walletAccount.id)
            true
        } else {
            false
        }
    }

    override fun getAccounts(): List<WalletAccount<*>> {
        return accounts.values.toList()
    }

    override fun getAccountById(id: UUID): WalletAccount<*>? {
        return accounts[id]
    }

    private fun createAccountContext(uuid: UUID, isReadOnly: Boolean = false): FioAccountContext {
        val accountContextInDB = backing.loadAccountContext(uuid)
        return if (accountContextInDB != null) {
            FioAccountContext(accountContextInDB.uuid,
                    accountContextInDB.currency,
                    accountContextInDB.accountName,
                    accountContextInDB.balance,
                    backing::updateAccountContext,
                    accountContextInDB.accountIndex,
                    accountContextInDB.archived,
                    accountContextInDB.blockHeight)
        } else {
            FioAccountContext(
                    uuid,
                    coinType,
                    if (isReadOnly) "FIO Read-Only" else "FIO ${getCurrentBip44Index() + 2}",
                    Balance.getZeroBalance(coinType),
                    backing::updateAccountContext,
                    if (isReadOnly) 0 else getCurrentBip44Index() + 1)
        }
    }

    fun getBip44Path(account: FioAccount): HdKeyPath? =
            HdKeyPath.valueOf("m/44'/235'/${account.accountIndex}'/0/0")

    companion object {
        const val ID: String = "FIO"
    }
}

fun WalletManager.getFioAccounts() = getAccounts().filter { it is FioAccount && it.isVisible }
fun WalletManager.getActiveFioAccounts() = getAccounts().filter { it is FioAccount && it.isVisible && it.isActive }