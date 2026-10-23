package com.github.magicalmuggle.web;

import java.io.Closeable;
import java.io.IOException;

// 搜索能力抽象：真实实现连 Elasticsearch，测试可以换成替身。
public interface SearchService extends Closeable {
    SearchResult search(String keyword, int from, int size) throws IOException;
}
