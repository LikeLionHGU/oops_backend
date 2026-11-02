package com.example.oops.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

    @Query("select i from UserIdentity i join fetch i.user where i.provider = :provider and i.issuer = :issuer and i.subject = :subject")
    Optional<UserIdentity> find(@Param("provider") String provider, @Param("issuer") String issuer,
                                @Param("subject") String subject);
}
