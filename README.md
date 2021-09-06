# Annotations on Steroids

# How to use
1. Copy and paste this in your project (to be able to use the boolean properties):
```java
package asteroids;
/* Expensive method invocation */
public @interface Expensive {
    boolean singleCall() default true;
    boolean calledInLoop() default true;
    boolean calledInLambda() default true;
}
```
2. Add the Expensive class in expensive annotations list
![immagine](https://user-images.githubusercontent.com/27242001/132225859-a8a6a9fc-03fe-4c20-951d-ebcfa36336be.png)




# Plugin description

<!-- Plugin description -->
Adds some useful annotations features to Java.

- "Expensive" annotation type: Allows you to mark some annotations to be used to mark code as "expensive" (high CPU consuming). For example create an annotation called @Expensive and mark it.
- more to come...

[Sourcecode](https://github.com/LoneDev6/AnnotationsOnSteroids)

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
<!-- Plugin description end -->
