-- Collective Launchpad schema. Money is stored as whole paise.

create table groups (
    id            bigserial primary key,
    name          text        not null,
    tagline       text,
    description   text,
    location      text,
    contact_email text        not null,
    created_at    timestamptz not null default now()
);

create table admins (
    id            bigserial primary key,
    group_id      bigint      not null references groups (id),
    email         text        not null unique,
    password_hash text        not null,
    created_at    timestamptz not null default now()
);

create table sellers (
    id                bigserial primary key,
    group_id          bigint      not null references groups (id),
    slug              text unique,
    name              text        not null,
    shop_name         text,
    bio               text,
    location          text,
    language          text,
    whatsapp          text,
    status            text        not null default 'draft'
        check (status in ('draft', 'pending_review', 'published', 'hidden')),
    -- Profile fields the agent was unsure of, comma separated (shop_name,bio,location)
    review_fields     text        not null default '',
    verified_at       timestamptz,
    live_check_ok     boolean,
    live_check_at     timestamptz,
    live_check_detail text,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

create table products (
    id            bigserial primary key,
    seller_id     bigint      not null references sellers (id) on delete cascade,
    name          text        not null,
    description   text        not null default '',
    price_paise   bigint check (price_paise >= 0),
    unit          text        not null default '',
    image_path    text,
    is_available  boolean     not null default true,
    needs_review  boolean     not null default false,
    -- Which values the agent was unsure of, comma separated (name,description,price,unit,photo)
    review_fields text        not null default '',
    sort_order    int         not null default 0,
    created_at    timestamptz not null default now()
);
create index products_seller_idx on products (seller_id, sort_order);

create table onboarding_sessions (
    id                   bigserial primary key,
    seller_id            bigint      not null references sellers (id) on delete cascade,
    -- Conversation history, append-only, images stored as references to uploaded files
    messages             jsonb       not null default '[]',
    status               text        not null
        check (status in ('working', 'needs_input', 'submitted', 'failed')),
    pending_question     text,
    -- tool_result blocks that must open the next user message
    pending_tool_results jsonb       not null default '[]',
    -- The user message being processed (kept so a failed turn can be retried)
    inflight_input       jsonb,
    last_error           text,
    note_for_admin       text,
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now()
);
create index onboarding_sessions_seller_idx on onboarding_sessions (seller_id);

create table enquiries (
    id             bigserial primary key,
    seller_id      bigint      not null references sellers (id) on delete cascade,
    customer_name  text,
    customer_email text,
    customer_phone text,
    message        text        not null,
    category       text,
    urgency        text,
    language       text,
    status         text        not null default 'new'
        check (status in ('new', 'drafted', 'replied', 'closed')),
    ai_error       text,
    created_at     timestamptz not null default now()
);
create index enquiries_status_idx on enquiries (status, created_at);

create table replies (
    id           bigserial primary key,
    enquiry_id   bigint      not null references enquiries (id) on delete cascade,
    body         text        not null,
    check_points jsonb       not null default '[]',
    drafted_by   text        not null check (drafted_by in ('agent', 'admin')),
    status       text        not null check (status in ('draft', 'approved', 'sent', 'discarded')),
    approved_by  bigint references admins (id),
    sent_at      timestamptz,
    send_error   text,
    created_at   timestamptz not null default now()
);
create index replies_enquiry_idx on replies (enquiry_id);

-- Every email the service sends, or would send when SMTP is not configured.
create table email_log (
    id           bigserial primary key,
    from_address text        not null,
    to_address   text        not null,
    reply_to     text,
    subject      text        not null,
    body         text        not null,
    result       text        not null,
    created_at   timestamptz not null default now()
);
