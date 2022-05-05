create table news
(
    id          bigint primary key auto_increment,
    title       text,
    content     text,
    url         varchar(4096),
    created_at  timestamp default current_timestamp,
    modified_at timestamp default current_timestamp on update current_timestamp
);

create table links_to_be_processed
(
    link varchar(4096)
);
create table links_already_processed
(
    link varchar(4096)
);
