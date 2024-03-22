package su.sres.securesms.database.model

import su.sres.securesms.database.IdentityDatabase
import org.whispersystems.libsignal.IdentityKey

data class IdentityStoreRecord(
  val addressName: String,
  val identityKey: IdentityKey,
  val verifiedStatus: IdentityDatabase.VerifiedStatus,
  val firstUse: Boolean,
  val timestamp: Long,
  val nonblockingApproval: Boolean
)