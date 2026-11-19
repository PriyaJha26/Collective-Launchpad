-- Messages sent to the group admin from the group's main site:
--   join     - a seller asking for a shop in the group (intake form)
--   question - anyone contacting the group
create table group_requests (
    id          bigserial primary key,
    kind        text        not null check (kind in ('join', 'question')),
    name        text        not null,
    email       text,
    phone       text,
    location    text,
    language    text,
    craft       text,
    message     text,
    status      text        not null default 'new'
        check (status in ('new', 'in_progress', 'done', 'declined')),
    -- The seller created from a join request, once onboarding starts
    seller_id   bigint references sellers (id) on delete set null,
    reply_sent  text,
    replied_at  timestamptz,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);
create index group_requests_status_idx on group_requests (status, created_at);
