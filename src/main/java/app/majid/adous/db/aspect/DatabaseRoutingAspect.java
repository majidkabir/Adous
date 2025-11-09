package app.majid.adous.db.aspect;

import app.majid.adous.db.config.DatabaseContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DatabaseRoutingAspect {

    @Around("@annotation(app.majid.adous.db.aspect.UseDatabase)")
    public Object switchDb(ProceedingJoinPoint pjp) throws Throwable {
        try {
            DatabaseContextHolder.setCurrentDb(getDbName(pjp));
            return pjp.proceed();
        } finally {
            DatabaseContextHolder.clear();
        }
    }

    private String getDbName(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        UseDatabase useDatabase = method.getAnnotation(UseDatabase.class);

        String[] paramNames = sig.getParameterNames();
        Object[] args = pjp.getArgs();
        String dbParamName = useDatabase.value().isEmpty() ? "dbName" : useDatabase.value();

        for (int i = 0; i < paramNames.length; i++) {
            if (dbParamName.equals(paramNames[i]) && args[i] instanceof String) {
                return (String) args[i];
            }
        }
        throw new IllegalArgumentException("Database name parameter '" + dbParamName + "' not found in method " + sig.getName());
    }
}
