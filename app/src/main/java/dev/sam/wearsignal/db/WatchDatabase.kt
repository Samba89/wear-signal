package dev.sam.wearsignal.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Single SQLite database holding the Signal protocol stores (per account identity: "aci"/"pni"),
 * received messages, and the contact-name cache.
 */
class WatchDatabase(context: Context) : SQLiteOpenHelper(context, "wearsignal.db", null, 5) {

  override fun onCreate(db: SQLiteDatabase) {
    createDirectoryTable(db)
    createGroupsTable(db)
    db.execSQL(
      """
      CREATE TABLE identities (
        account TEXT NOT NULL,
        address TEXT NOT NULL,
        identity_key BLOB NOT NULL,
        added_at INTEGER NOT NULL,
        PRIMARY KEY (account, address)
      )
      """
    )
    db.execSQL(
      """
      CREATE TABLE sessions (
        account TEXT NOT NULL,
        address TEXT NOT NULL,
        device INTEGER NOT NULL,
        record BLOB NOT NULL,
        PRIMARY KEY (account, address, device)
      )
      """
    )
    db.execSQL(
      """
      CREATE TABLE one_time_prekeys (
        account TEXT NOT NULL,
        key_id INTEGER NOT NULL,
        record BLOB NOT NULL,
        stale_at INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (account, key_id)
      )
      """
    )
    db.execSQL(
      """
      CREATE TABLE signed_prekeys (
        account TEXT NOT NULL,
        key_id INTEGER NOT NULL,
        record BLOB NOT NULL,
        PRIMARY KEY (account, key_id)
      )
      """
    )
    db.execSQL(
      """
      CREATE TABLE kyber_prekeys (
        account TEXT NOT NULL,
        key_id INTEGER NOT NULL,
        record BLOB NOT NULL,
        is_last_resort INTEGER NOT NULL DEFAULT 0,
        stale_at INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (account, key_id)
      )
      """
    )
    db.execSQL(
      """
      CREATE TABLE used_kyber_tuples (
        account TEXT NOT NULL,
        kyber_key_id INTEGER NOT NULL,
        signed_key_id INTEGER NOT NULL,
        base_key BLOB NOT NULL,
        UNIQUE (account, kyber_key_id, signed_key_id, base_key)
      )
      """
    )
    db.execSQL(
      """
      CREATE TABLE sender_keys (
        account TEXT NOT NULL,
        address TEXT NOT NULL,
        device INTEGER NOT NULL,
        distribution_id TEXT NOT NULL,
        record BLOB NOT NULL,
        created_at INTEGER NOT NULL,
        PRIMARY KEY (account, address, device, distribution_id)
      )
      """
    )
    db.execSQL(
      """
      CREATE TABLE messages (
        _id INTEGER PRIMARY KEY AUTOINCREMENT,
        peer TEXT NOT NULL,
        sender_aci TEXT NOT NULL,
        group_id TEXT,
        body TEXT NOT NULL,
        sent_at INTEGER NOT NULL,
        server_at INTEGER NOT NULL,
        from_self INTEGER NOT NULL DEFAULT 0,
        delivered_at INTEGER NOT NULL DEFAULT 0,
        read_at INTEGER NOT NULL DEFAULT 0
      )
      """
    )
    db.execSQL(
      """
      CREATE TABLE contacts (
        aci TEXT PRIMARY KEY,
        profile_key BLOB,
        name TEXT,
        fetched_at INTEGER NOT NULL DEFAULT 0,
        avatar_fetched_at INTEGER NOT NULL DEFAULT 0
      )
      """
    )
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      createDirectoryTable(db)
    }
    if (oldVersion < 3) {
      createGroupsTable(db)
      // Conversation key: group id for groups, the other party's ACI for 1:1. Legacy rows
      // stored the recipient in sender_aci for watch-sent messages, so COALESCE covers both
      // directions; phone-synced self messages (sender_aci = own ACI) end up in a stale
      // self-keyed conversation that prunes out naturally.
      db.execSQL("ALTER TABLE messages ADD COLUMN peer TEXT NOT NULL DEFAULT ''")
      db.execSQL("UPDATE messages SET peer = COALESCE(group_id, sender_aci)")
    }
    if (oldVersion < 4) {
      // Avatar fetches are tracked separately from name/state fetches so contacts and
      // groups that are already "fresh" still get their photo backfilled once.
      db.execSQL("ALTER TABLE contacts ADD COLUMN avatar_fetched_at INTEGER NOT NULL DEFAULT 0")
      db.execSQL("ALTER TABLE groups ADD COLUMN avatar_fetched_at INTEGER NOT NULL DEFAULT 0")
    }
    if (oldVersion < 5) {
      // Delivery/read receipt status for our own sent messages (matched by sent_at).
      db.execSQL("ALTER TABLE messages ADD COLUMN delivered_at INTEGER NOT NULL DEFAULT 0")
      db.execSQL("ALTER TABLE messages ADD COLUMN read_at INTEGER NOT NULL DEFAULT 0")
    }
  }

  /** GroupsV2 state cache: master key harvested from message contexts, title/members fetched from the group server. */
  private fun createGroupsTable(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE groups (
        group_id TEXT PRIMARY KEY,
        master_key BLOB NOT NULL,
        revision INTEGER NOT NULL DEFAULT 0,
        title TEXT,
        members TEXT,
        fetched_at INTEGER NOT NULL DEFAULT 0,
        avatar_fetched_at INTEGER NOT NULL DEFAULT 0
      )
      """
    )
  }

  /** Discovered Signal contacts (phone number → ACI), cached from a CDSI lookup of the watch's contacts. */
  private fun createDirectoryTable(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE directory (
        e164 TEXT PRIMARY KEY,
        aci TEXT NOT NULL,
        name TEXT
      )
      """
    )
  }
}
