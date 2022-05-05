create table NEWS
(
    id          bigint primary key auto_increment,
    title       text,
    content     text,
    url         varchar(4096),
    created_at  timestamp,
    modified_at timestamp
);

create table LINKS_TO_BE_PROCESSED
(
    link varchar(4096)
);
create table LINKS_ALREADY_PROCESSED
(
    link varchar(4096)
);
