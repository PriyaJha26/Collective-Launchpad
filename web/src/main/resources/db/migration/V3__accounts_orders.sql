-- Three roles: superadmin (runs the site), seller (owns one shop), customer (buys).
-- Everyone logs in with a phone number (or, for the superadmin, an email) and a password.
create table users (
    id            bigserial primary key,
    role          text        not null check (role in ('superadmin', 'seller', 'customer')),
    name          text        not null,
    phone         text unique,              -- 10-digit Indian mobile number
    email         text unique,              -- lowercase
    password_hash text        not null,
    seller_id     bigint unique references sellers (id) on delete set null,
    language      text,
    active        boolean     not null default true,
    created_at    timestamptz not null default now(),
    last_login_at timestamptz,
    check (phone is not null or email is not null)
);

-- Existing group admins become superadmins, keeping their ids.
insert into users (id, role, name, email, password_hash, created_at)
select id, 'superadmin', 'Superadmin', lower(email), password_hash, created_at from admins;
select setval(pg_get_serial_sequence('users', 'id'), coalesce((select max(id) from users), 0) + 1, false);

alter table replies drop constraint replies_approved_by_fkey;
alter table replies add constraint replies_approved_by_fkey foreign key (approved_by) references users (id) on delete set null;
drop table admins;

-- Sellers: where customers pay them, and how the shop was created.
alter table sellers add column upi_id text;
alter table sellers add column registered_by text not null default 'admin' check (registered_by in ('admin', 'self'));

-- Stock: null means "made to order" (not counted).
alter table products add column stock integer check (stock >= 0);

-- Enquiries: what the customer wants, and their account when they were logged in.
alter table enquiries add column topic text check (topic in ('question', 'discount', 'custom', 'order'));
alter table enquiries add column customer_user_id bigint references users (id) on delete set null;
create index enquiries_customer_idx on enquiries (customer_user_id);

-- Join requests written in a local language get an English translation for the superadmin.
alter table group_requests add column translation_en jsonb;
alter table group_requests add column source_language text;

-- Orders: one order is for one shop. Customers pay the seller directly (cash or UPI);
-- the seller confirms payment.
create table orders (
    id                      bigserial primary key,
    seller_id               bigint      not null references sellers (id),
    customer_id             bigint      not null references users (id),
    status                  text        not null default 'placed'
        check (status in ('placed', 'accepted', 'ready', 'completed', 'declined', 'cancelled')),
    payment_method          text        not null check (payment_method in ('cod', 'upi')),
    payment_status          text        not null default 'unpaid' check (payment_status in ('unpaid', 'paid')),
    customer_marked_paid_at timestamptz,
    paid_at                 timestamptz,
    total_paise             bigint      not null check (total_paise >= 0),
    customer_name           text        not null,
    customer_phone          text        not null,
    delivery                text        not null,  -- delivery address, or "Pickup"
    note                    text,
    seller_note             text,                  -- e.g. reason for declining
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now()
);
create index orders_seller_idx on orders (seller_id, created_at desc);
create index orders_customer_idx on orders (customer_id, created_at desc);

create table order_items (
    id          bigserial primary key,
    order_id    bigint not null references orders (id) on delete cascade,
    product_id  bigint references products (id) on delete set null,
    name        text   not null,       -- copied at order time
    unit        text   not null default '',
    price_paise bigint not null check (price_paise >= 0),
    quantity    int    not null check (quantity > 0 and quantity <= 1000)
);
create index order_items_order_idx on order_items (order_id);
