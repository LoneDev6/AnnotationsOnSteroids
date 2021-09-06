package asteroids;

/**
 * Expensive method invocation
 */
public @interface Expensive
{
    boolean singleCall() default true;
    boolean calledInLoop() default true;
    boolean calledInLambda() default true;
}
