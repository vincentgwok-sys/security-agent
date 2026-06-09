package com.security.agent.service;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Component
public class PromptTemplateEngine {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateEngine.class);

    private final MustacheFactory mf = new DefaultMustacheFactory();

    /**
     * 从 classpath 下的 prompts/ 目录加载模板并渲染。
     *
     * @param templateName 模板文件名（不含 .mustache 后缀）
     * @param variables    模板变量
     * @return 渲染后的 Prompt 文本
     */
    public String render(String templateName, Map<String, Object> variables) {
        try {
            String templateContent = loadTemplateFile(templateName);
            Mustache mustache = mf.compile(new StringReader(templateContent), templateName);
            StringWriter writer = new StringWriter();
            mustache.execute(writer, variables).flush();
            return writer.toString();
        } catch (Exception e) {
            log.error("Prompt 模板渲染失败: {}", templateName, e);
            throw new RuntimeException("Prompt 模板渲染失败: " + templateName, e);
        }
    }

    private String loadTemplateFile(String templateName) throws Exception {
        String resourcePath = "prompts/" + templateName + ".mustache";
        java.net.URL url = getClass().getClassLoader().getResource(resourcePath);
        if (url == null) {
            throw new IllegalArgumentException("Prompt 模板文件不存在: " + resourcePath);
        }
        Path path = Path.of(url.toURI());
        return Files.readString(path);
    }
}
