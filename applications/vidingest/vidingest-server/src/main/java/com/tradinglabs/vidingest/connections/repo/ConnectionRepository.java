package com.tradinglabs.vidingest.connections.repo;

import com.tradinglabs.vidingest.api.connections.ConnectionName;
import com.tradinglabs.vidingest.connections.domain.Connection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectionRepository extends JpaRepository<Connection, ConnectionName> {
}
