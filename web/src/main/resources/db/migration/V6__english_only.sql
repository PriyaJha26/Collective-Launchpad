-- The site is English-only now: drop every language and translation column.

alter table sellers drop column language;
alter table sellers drop column i18n;
alter table sellers drop column i18n_source_hash;

alter table products drop column i18n;
alter table products drop column i18n_source_hash;

alter table users drop column language;

alter table enquiries drop column language;
alter table enquiries drop column customer_language;
alter table enquiries drop column translations;

alter table replies drop column language;
alter table replies drop column translations;

alter table orders drop column customer_language;
alter table orders drop column note_translations;
alter table orders drop column seller_note_translations;

alter table group_requests drop column language;
alter table group_requests drop column translation_en;
alter table group_requests drop column source_language;
