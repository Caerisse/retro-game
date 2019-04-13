-- @formatter:off

drop table messages;

create table private_messages (
  id bigserial primary key,
  sender_id bigint references users on delete set null,
  recipient_id bigint references users on delete set null,
  deleted_by_sender boolean not null,
  deleted_by_recipient boolean not null,
  at timestamptz not null,
  message text not null
);

create index private_messages_sender_id_deleted_by_sender_at_idx
          on private_messages (sender_id, deleted_by_sender, at desc);
create index private_messages_recipient_id_deleted_by_recipient_at_idx
          on private_messages (recipient_id, deleted_by_recipient, at desc);
