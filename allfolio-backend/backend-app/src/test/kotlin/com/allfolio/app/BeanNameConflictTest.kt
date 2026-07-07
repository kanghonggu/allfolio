package com.allfolio.app

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.SimpleBeanDefinitionRegistry
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner
import org.springframework.core.type.filter.RegexPatternTypeFilter
import java.util.regex.Pattern

class BeanNameConflictTest {

    @Test
    fun `component scan of all production packages registers without bean name conflicts`() {
        val registry = SimpleBeanDefinitionRegistry()
        val scanner = ClassPathBeanDefinitionScanner(registry, true)
        scanner.addExcludeFilter(RegexPatternTypeFilter(Pattern.compile(".*Test.*")))

        // Throws ConflictingBeanDefinitionException if two classes map to the same bean name,
        // which is exactly what breaks Spring startup in production.
        scanner.scan("com.allfolio")
    }
}
