create table if not exists operator_event_outbox
(
    id              uuid primary key,
    event_type      varchar(64)              not null,
    payload         jsonb                    not null,
    status          varchar(16)              not null default 'PENDING',
    attempts        int                      not null default 0,
    last_error      text,
    last_attempt_at timestamp with time zone,
    next_attempt_at timestamp with time zone not null default now(),
    created_at      timestamp with time zone not null default now(),
    sent_at         timestamp with time zone,
    constraint chk_outbox_status check (status in ('PENDING', 'CHECK', 'FAILED'))
);

create index idx_outbox_pending
    on operator_event_outbox (next_attempt_at)
    where status = 'PENDING';

create index idx_outbox_status_sent_at
    on operator_event_outbox (status, sent_at);
