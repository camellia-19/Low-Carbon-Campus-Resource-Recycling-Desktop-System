package edu.campus.recycle.integration;

import edu.campus.recycle.entity.User;
import edu.campus.recycle.exception.AccountLockedException;
import edu.campus.recycle.exception.AuthenticationException;
import edu.campus.recycle.exception.DuplicateEntityException;
import edu.campus.recycle.exception.PermissionDeniedException;
import edu.campus.recycle.exception.ValidationException;
import edu.campus.recycle.service.UserService;
import edu.campus.recycle.support.TestDatabase;
import edu.campus.recycle.util.DaoFactory;
import edu.campus.recycle.util.ServiceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class UserServiceTest {
    private static final String PASSWORD = "LocalTest123";
    private final UserService users = ServiceFactory.getInstance().getUserService();
    private final DaoFactory dao = DaoFactory.getInstance();
    @TempDir Path temp;
    private TestDatabase database;

    @BeforeEach
    void initializeDatabase() {
        database = new TestDatabase(temp);
    }

    @AfterEach
    void restoreDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void registerStoresDigestAndLoginReturnsAccount() {
        User created = register();
        User stored = users.findById(created.getId());
        var result = users.login(created.getUsername(), PASSWORD);
        assertEquals("STUDENT", stored.getRole());
        assertEquals(100, stored.getCreditScore());
        assertNotEquals(PASSWORD, stored.getPasswordHash());
        assertTrue(result.isSuccess());
        assertEquals(created.getId(), result.getUserId());
    }

    @Test
    void rejectsDuplicateUsernameWithoutCreatingSecondUser() {
        User created = register();
        var error = assertThrows(DuplicateEntityException.class,
                () -> users.register(created.getUsername(), PASSWORD, "测试学生", "20260002", null, null, null));
        assertEquals("DUP-001", error.getCode());
        assertEquals(1, dao.getUserDao().findAll().size());
    }

    @Test
    void duplicateStudentNumberHasItsOwnErrorCode() {
        User user = register();
        var error = assertThrows(DuplicateEntityException.class, () -> users.register(
                "student_other", PASSWORD, "测试学生", user.getStudentNo(), null, null, null));
        assertEquals("DUP-002", error.getCode());
        assertFalse(error.getUserMessage().contains(user.getStudentNo()));
        assertEquals(1, dao.getUserDao().findAll().size());
    }

    @Test
    void selfRegistrationRejectsReservedRole() {
        assertThrows(PermissionDeniedException.class,
                () -> users.register("student_test", PASSWORD, "测试学生", "20260001", "ADMIN", null, null));
        assertTrue(dao.getUserDao().findAll().isEmpty());
    }

    @Test
    void frozenAccountCannotLoginUntilAdministratorUnfreezesIt() {
        User user = register();
        User admin = users.register("test_operator", PASSWORD, "测试管理员", null, null, null, null);
        admin.setRole("ADMIN");
        dao.getUserDao().update(admin);
        assertThrows(PermissionDeniedException.class, () -> users.freezeUser(user.getId(), user.getId()));
        users.freezeUser(admin.getId(), user.getId());
        var error = assertThrows(AuthenticationException.class, () -> users.login(user.getUsername(), PASSWORD));
        assertEquals("BR-03", error.getCode());
        users.unfreezeUser(admin.getId(), user.getId());
        assertTrue(users.login(user.getUsername(), PASSWORD).isSuccess());
    }

    @Test
    void fifthConsecutiveFailureLocksEvenWithCorrectPassword() {
        User user = register();
        for (int attempt = 1; attempt <= 4; attempt++) {
            var error = assertThrows(AuthenticationException.class, () -> users.login(user.getUsername(), "wrong"));
            assertEquals("AUTH-001", error.getCode());
            assertEquals(attempt, users.findById(user.getId()).getFailCount());
        }
        LocalDateTime earliestExpiry = LocalDateTime.now().withNano(0).plusMinutes(15);
        var lock = assertThrows(AccountLockedException.class, () -> users.login(user.getUsername(), "wrong"));
        assertEquals("LOCK-001", lock.getCode());
        User locked = users.findById(user.getId());
        assertEquals(5, locked.getFailCount());
        assertFalse(locked.getLockedUntil().isBefore(earliestExpiry));
        assertFalse(locked.getLockedUntil().isAfter(LocalDateTime.now().plusMinutes(15)));
        assertThrows(AccountLockedException.class, () -> users.login(user.getUsername(), PASSWORD));
    }

    @Test
    void successfulLoginResetsFailureCounterAfterLockExpires() {
        User user = register();
        dao.getUserDao().updateFailCount(user.getId(), 5, LocalDateTime.now().minusMinutes(1));
        assertTrue(users.login(user.getUsername(), PASSWORD).isSuccess());
        User stored = users.findById(user.getId());
        assertEquals(0, stored.getFailCount());
        assertNull(stored.getLockedUntil());
        assertNotNull(stored.getLastLoginAt());
    }

    @Test
    void passwordChangeAcceptsOnlyTheNewPasswordAndRotatesSalt() {
        User user = register();
        String newPassword = "UpdatedTest123";
        users.changePassword(user.getId(), PASSWORD, newPassword);
        assertThrows(AuthenticationException.class, () -> users.login(user.getUsername(), PASSWORD));
        assertTrue(users.login(user.getUsername(), newPassword).isSuccess());
        assertNotEquals(user.getSalt(), users.findById(user.getId()).getSalt());
    }

    @Test
    void invalidPasswordChangesKeepDigestAndReturnSpecificCodes() {
        User user = register();
        var wrongOld = assertThrows(AuthenticationException.class,
                () -> users.changePassword(user.getId(), "wrong", "UpdatedTest123"));
        assertEquals("VAL-003", wrongOld.getCode());
        var weakNew = assertThrows(ValidationException.class,
                () -> users.changePassword(user.getId(), PASSWORD, "password"));
        assertEquals("VAL-002", weakNew.getCode());
        var unchanged = assertThrows(ValidationException.class,
                () -> users.changePassword(user.getId(), PASSWORD, PASSWORD));
        assertEquals("VAL-008", unchanged.getCode());
        assertEquals(user.getPasswordHash(), users.findById(user.getId()).getPasswordHash());
    }

    @Test
    void passwordChangeClearsFailureStateAndWritesAuditLog() {
        User user = register();
        dao.getUserDao().updateFailCount(user.getId(), 5, LocalDateTime.now().minusMinutes(1));
        users.changePassword(user.getId(), PASSWORD, "UpdatedTest123");
        User stored = users.findById(user.getId());
        assertEquals(0, stored.getFailCount());
        assertNull(stored.getLockedUntil());
        assertTrue(dao.getOperationLogDao().findAll().stream().anyMatch(log -> "CHG_PWD".equals(log.getOperation())
                && user.getId().equals(log.getOperatorId())));
    }

    @Test
    void expiredLockStartsNewFailureWindow() {
        User user = register();
        dao.getUserDao().updateFailCount(user.getId(), 5, LocalDateTime.now().minusMinutes(1));
        var error = assertThrows(AuthenticationException.class, () -> users.login(user.getUsername(), "wrong"));
        assertEquals("AUTH-001", error.getCode());
        assertEquals(1, users.findById(user.getId()).getFailCount());
        assertNull(users.findById(user.getId()).getLockedUntil());
    }

    @Test
    void creditUpdatesApplyBoundsAndKeepSnapshots() {
        User user = register();
        users.updateCredit(user.getId(), 80, null, "测试上限");
        assertEquals(150, users.findById(user.getId()).getCreditScore());
        users.updateCredit(user.getId(), -200, null, "测试下限");
        assertEquals(0, users.findById(user.getId()).getCreditScore());

        var snapshots = dao.getCreditLogDao().findByUserId(user.getId(), 10);
        assertEquals(2, snapshots.size());
        var credit = users.getCredit(user.getId());
        assertEquals(0, credit.getCreditScore());
        assertEquals(2, credit.getChangeCount());
        assertTrue(snapshots.stream().anyMatch(log -> log.getDelta() == 50 && log.getScoreAfter() == 150));
        assertTrue(snapshots.stream().anyMatch(log -> log.getDelta() == -150 && log.getScoreAfter() == 0));
    }

    private User register() {
        return users.register("student_test", PASSWORD, "测试学生", "20260001", null, null, null);
    }
}
