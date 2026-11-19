-- Everyone reads the website in their own language. Seller-written texts and the messages between
-- customers and sellers are translated by Claude and stored here, keyed by language code.

-- Shop and product texts: {"hi": {"shop_name": "...", "bio": "..."}, "ta": {...}}.
-- The hash is of the source texts the translations were made from; a changed text is translated again.
alter table sellers add column i18n jsonb;
alter table sellers add column i18n_source_hash text;
alter table products add column i18n jsonb;
alter table products add column i18n_source_hash text;

-- Messages: the customer's language when they wrote, and the message in other languages: {"mr": "..."}.
alter table enquiries add column customer_language text;
alter table enquiries add column translations jsonb;

-- Replies: the language the reply was written in, and its translations: {"ta": "..."}.
alter table replies add column language text;
alter table replies add column translations jsonb;

-- Order notes in the other side's language.
alter table orders add column customer_language text;
alter table orders add column note_translations jsonb;
alter table orders add column seller_note_translations jsonb;
