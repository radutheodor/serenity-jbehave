package net.serenity_bdd.jbehave;

import ch.lambdaj.function.convert.PropertyExtractor;
import org.jbehave.core.annotations.Given;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static ch.lambdaj.Lambda.convert;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

public class WhenLoadingClassesFromAPackage {

    @Test
    public void shouldLoadAllClassesInAPackage() throws IOException, ClassNotFoundException {
        List<Class<?>> classes = ClassFinder.loadClasses().fromPackage("net.serenity_bdd.jbehave.pages");
        assertThat(classes.size(), is(1));
        assertThat(classes.get(0).getName(), is("net.serenity_bdd.jbehave.pages.StaticSitePage"));
    }

    @Test
    public void shouldLoadAllClassesInNestedPackages() throws IOException, ClassNotFoundException {
        List<Class<?>> classes = ClassFinder.loadClasses().fromPackage("net.serenity_bdd.jbehave");
        assertThat(classes.size(), greaterThan(10));
    }

    @Test
    public void shouldLoadAllAnnotatedClassesInNestedPackages() throws IOException, ClassNotFoundException {
        List<Class<?>> classes = ClassFinder.loadClasses().annotatedWith(Given.class).fromPackage("net.serenity_bdd.jbehave");
        assertThat(classes.size(), greaterThan(10));
    }

    @Test
    public void shouldLoadNoClassesIfThePackageDoesNotExist() throws IOException, ClassNotFoundException {
        List<Class<?>> classes = ClassFinder.loadClasses().fromPackage("that.does.not.exist");
        assertThat(classes.size(), is(0));
    }


    @Test
    public void shouldNotLoadResourcesOnClasspath() throws IOException, ClassNotFoundException {
        List<Class<?>> classes = ClassFinder.loadClasses().fromPackage("stories");
        assertThat(classes.size(), is(0));
    }

    @Test
    public void shouldLoadClassesFromDependencies() throws IOException, ClassNotFoundException {
        List<Class<?>> classes = ClassFinder.loadClasses().annotatedWith(Ignore.class).fromPackage("net.thucydides.jbehave");
        List<String> classnames = convert(classes, new PropertyExtractor("name"));
        assertThat(classnames, hasItem("net.thucydides.jbehave.SomeBoilerplateSteps"));
    }

    @Test
    public void  shouldLoadAllClassesInAGivenPackageFromAnotherModuleAndAllSubpackages() {
        List<Class<?>> classes = ClassFinder.loadClasses().fromPackage("net.thucydides.jbehave");
        List<String> classnames = convert(classes, new PropertyExtractor("name"));
        assertThat(classnames, hasItem("net.thucydides.jbehave.SomeBoilerplateSteps"));
    }

    // enable testing from an IDE, where otherwise the classpath is setup to depend directly on .class files, without packaging to .jar
    @Test
    public void shouldLoadClassesInAGivenPackageFromADependencyJar() throws Exception {
        List<Class<?>> classes = net.thucydides.core.reflection.ClassFinder.loadClasses().fromPackage("org.junit.runners");
        List<String> classnames = convert(classes, new PropertyExtractor("name"));
        assertThat(classnames, hasItem("org.junit.runners.JUnit4"));
    }

    // enable testing from an IDE, where otherwise the classpath is setup to depend directly on .class files, without packaging to .jar
    @Test
    public void shouldLoadNestedClassesInAGivenPackageFromADependencyJar() throws Exception {
        List<Class<?>> classes = net.thucydides.core.reflection.ClassFinder.loadClasses().fromPackage("org.junit.runners");
        List<String> classnames = convert(classes, new PropertyExtractor("name"));
        assertThat(classnames, hasItem("org.junit.runners.model.RunnerScheduler"));
    }

    // enable testing from an IDE, where otherwise the classpath is setup to depend directly on .class files, without packaging to .jar
    @Test
    public void shouldLoadAnnotatedClassesInAGivenPackageFromADependencyJar() throws Exception {
        List<Class<?>> classes = net.thucydides.core.reflection.ClassFinder.loadClasses()
                                                                           .annotatedWith(Deprecated.class)
                                                                           .fromPackage("junit.framework");
        List<String> classnames = convert(classes, new PropertyExtractor("name"));
        assertThat(classnames, hasItem("junit.framework.Assert"));
    }
}
