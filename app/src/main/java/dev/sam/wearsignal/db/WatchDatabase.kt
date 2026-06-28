package dev.sam.wearsignal.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Single SQLite database holding the Signal protocol stores (per account identity: "aci"/"pni"),
 * received messages, and the contact-name cache.
 */
class WatchDatabase(context: Context) : SQLiteOpenHelper(context, "wearsignal.db", null, 2) {

  override fun onCreate(db: SQLiteDatabase) {
    createDirectoryTable(db)
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
        sender_aci TEXT NOT NULL,
        group_id TEXT,
        body TEXT NOT NULL,
        sent_at INTEGER NOT NULL,
        server_at INTEGER NOT NULL,
        from_self INTEGER NOT NULL DEFAULT 0
      )
      """
    )
    db.execSQL(
      """
      CREATE TABLE contacts (
        aci TEXT PRIMARY KEY,
        profile_key BLOB,
        name TEXT,
        fetched_at INTEGER NOT NULL DEFAULT 0
      )
      """
    )
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      createDirectoryTable(db)
    }
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
