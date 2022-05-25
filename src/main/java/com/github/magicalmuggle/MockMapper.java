package com.github.magicalmuggle;

import java.util.List;

public interface MockMapper {
    void insertNews(News news);

    List<News> selectNews();

    Integer countNews();
}
