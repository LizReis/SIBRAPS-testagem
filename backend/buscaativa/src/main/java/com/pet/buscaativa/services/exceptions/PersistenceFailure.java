package com.pet.buscaativa.services.exceptions;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

import org.hibernate.JDBCException;
import org.hibernate.exception.JDBCConnectionException;
import org.hibernate.exception.LockAcquisitionException;
import org.hibernate.exception.SQLGrammarException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PessimisticLockException;

enum PersistenceFailure {
    // Evidence of a technical failure takes precedence over a lock translation.
    UNAVAILABLE,
    CONNECTION_TIMEOUT,
    CONNECTION,
    QUERY_TIMEOUT,
    INTEGRITY,
    DATABASE_OPERATION,
    VERSION,
    DEADLOCK,
    LOCK_TIMEOUT,
    CONCURRENCY,
    UNCLASSIFIED;

    static PersistenceFailure classify(List<Throwable> causes) {
        PersistenceFailure strongest = null;
        for (Throwable cause : causes) {
            var evidence = evidence(cause);
            if (evidence != null && (strongest == null || evidence.compareTo(strongest) < 0)) {
                strongest = evidence;
            }
        }
        return strongest;
    }

    private static PersistenceFailure evidence(Throwable cause) {
        if (cause instanceof SQLException sql) return sqlEvidence(sql);
        if (cause instanceof OptimisticLockException || cause instanceof OptimisticLockingFailureException)
            return VERSION;
        if (cause instanceof LockTimeoutException || cause instanceof org.hibernate.exception.LockTimeoutException)
            return LOCK_TIMEOUT;
        if (cause instanceof PessimisticLockException || cause instanceof PessimisticLockingFailureException
                || cause instanceof LockAcquisitionException)
            return CONCURRENCY;
        if (cause instanceof jakarta.persistence.QueryTimeoutException || cause instanceof QueryTimeoutException)
            return QUERY_TIMEOUT;
        if (cause instanceof DataIntegrityViolationException || cause instanceof org.hibernate.exception.ConstraintViolationException)
            return INTEGRITY;
        if (cause instanceof InvalidDataAccessResourceUsageException || cause instanceof SQLGrammarException)
            return DATABASE_OPERATION;
        if (cause instanceof JDBCConnectionException || cause instanceof DataAccessResourceFailureException)
            return CONNECTION;
        if (cause instanceof DataAccessException || cause instanceof PersistenceException) return UNCLASSIFIED;
        return null;
    }

    private static PersistenceFailure sqlEvidence(SQLException sql) {
        String state = sql.getSQLState() == null ? "" : sql.getSQLState();
        if (sql instanceof SQLNonTransientConnectionException) return CONNECTION;
        if (sql instanceof SQLTransientConnectionException || state.equals("57P01")
                || state.equals("57P03") || state.equals("08006") || state.equals("08S01"))
            return UNAVAILABLE;
        if (state.equals("HYT01")) return CONNECTION_TIMEOUT;
        if (state.startsWith("08")) return CONNECTION;
        if (sql instanceof SQLIntegrityConstraintViolationException || state.startsWith("23")) return INTEGRITY;
        if (sql instanceof SQLSyntaxErrorException || state.startsWith("42") || state.startsWith("22"))
            return DATABASE_OPERATION;
        int vendorCode = sql.getErrorCode();
        if (state.equals("40P01") || (state.equals("40001") && (vendorCode == 40001 || vendorCode == 1213)))
            return DEADLOCK;
        if (state.equals("55P03") || (state.equals("HYT00") && vendorCode == 50200)
                || (state.equals("HY000") && vendorCode == 1205)) return LOCK_TIMEOUT;
        if (state.equals("40001")) return CONCURRENCY;
        if (sql instanceof SQLTimeoutException || state.equals("57014") || state.equals("HYT00"))
            return QUERY_TIMEOUT;
        return UNCLASSIFIED;
    }

    static List<Throwable> causes(Throwable failure) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var pending = new ArrayDeque<Throwable>();
        var result = new ArrayList<Throwable>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable cause = pending.removeFirst();
            if (!visited.add(cause)) continue;
            result.add(cause);
            if (cause.getCause() != null) pending.addLast(cause.getCause());
            if (cause instanceof JDBCException jdbc && jdbc.getSQLException() != null)
                pending.addLast(jdbc.getSQLException());
            if (cause instanceof SQLException sql && sql.getNextException() != null)
                pending.addLast(sql.getNextException());
        }
        return result;
    }
}
