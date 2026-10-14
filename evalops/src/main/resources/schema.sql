create table if not exists dataset (
  id bigint auto_increment primary key,
  name varchar(200) not null,
  version int not null,
  sha256 varchar(64) not null,
  items int not null,
  created timestamp not null,
  unique (name, version)
);
create table if not exists dataset_item (
  dataset_id bigint not null,
  idx int not null,
  input clob not null,
  reference clob,
  meta clob,
  primary key (dataset_id, idx)
);
create table if not exists target (
  id bigint auto_increment primary key,
  name varchar(200) not null unique,
  url varchar(1000) not null,
  model varchar(200),
  params clob not null,
  created timestamp not null
);
create table if not exists run (
  id bigint auto_increment primary key,
  dataset_id bigint not null,
  target_id bigint not null,
  target_snapshot clob not null,
  metrics varchar(1000) not null,
  concurrency int not null,
  use_cache boolean not null,
  status varchar(20) not null,
  created timestamp not null,
  finished timestamp,
  error clob
);
create table if not exists result (
  run_id bigint not null,
  idx int not null,
  output clob,
  latency_ms double,
  error clob,
  scores clob not null,
  cached boolean not null,
  attempts int not null,
  primary key (run_id, idx)
);
create table if not exists response_cache (
  cache_key varchar(64) primary key,
  output clob not null,
  created timestamp not null
);
