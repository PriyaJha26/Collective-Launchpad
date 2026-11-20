-- "Continue with Google" for customers and sellers. The Google account id ("sub") links a login to
-- an account; people who only ever used Google have no password until they set one.
alter table users add column google_sub text unique;
alter table users alter column password_hash drop not null;
