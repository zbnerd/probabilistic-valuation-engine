package maple.expectation.infrastructure.persistence.repository;

import java.util.List;
import maple.expectation.domain.model.character.CharacterId;
import maple.expectation.domain.model.equipment.CharacterEquipment;
import maple.expectation.infrastructure.jdbc.JdbcBatchUpsertRepository;
import maple.expectation.infrastructure.persistence.CharacterEquipmentJpaRepository;
import maple.expectation.infrastructure.persistence.entity.CharacterEquipmentJpaEntity;
import maple.expectation.infrastructure.persistence.mapper.CharacterEquipmentMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter implementation of CharacterEquipment repository.
 *
 * <p>This is the <b>Adapter</b> in the Hexagonal Architecture pattern. It bridges the domain-layer
 * repository port with the infrastructure-layer Spring Data JPA repository.
 *
 * @see maple.expectation.domain.repository.CharacterEquipmentRepository
 */
@Repository
@Transactional
public class CharacterEquipmentRepositoryImpl
    implements maple.expectation.domain.repository.CharacterEquipmentRepository {

  private final CharacterEquipmentJpaRepository jpaRepo;
  private final JdbcBatchUpsertRepository jdbcBatchUpsertRepository;

  public CharacterEquipmentRepositoryImpl(
      CharacterEquipmentJpaRepository jpaRepo,
      JdbcBatchUpsertRepository jdbcBatchUpsertRepository) {
    this.jpaRepo = jpaRepo;
    this.jdbcBatchUpsertRepository = jdbcBatchUpsertRepository;
  }

  @Override
  @Transactional(readOnly = true)
  @Nullable public CharacterEquipment findById(CharacterId characterId) {
    if (characterId == null) {
      throw new IllegalArgumentException("CharacterId cannot be null");
    }
    return jpaRepo
        .findById(characterId.value())
        .map(CharacterEquipmentMapper::toDomain)
        .orElse(null);
  }

  @Override
  @Deprecated(forRemoval = true)
  public CharacterEquipment save(CharacterEquipment equipment) {
    if (equipment == null) {
      throw new IllegalArgumentException("Equipment cannot be null");
    }

    CharacterId id = equipment.characterId();
    CharacterEquipmentJpaEntity jpaEntity = jpaRepo.findById(id.value()).orElse(null);

    if (jpaEntity != null) {
      CharacterEquipmentMapper.updateJpaEntity(jpaEntity, equipment);
      jpaRepo.save(jpaEntity);
      return equipment;
    } else {
      jpaEntity = CharacterEquipmentMapper.toJpaEntity(equipment);
      jpaRepo.save(jpaEntity);
      return equipment;
    }
  }

  @Override
  public List<CharacterEquipment> saveAll(List<CharacterEquipment> equipments) {
    if (equipments == null) {
      throw new IllegalArgumentException("Equipments list cannot be null");
    }

    if (equipments.isEmpty()) {
      return List.of();
    }

    // Use JDBC batch for 33x performance improvement
    jdbcBatchUpsertRepository.batchUpsert(equipments);
    return equipments;
  }

  @Override
  public void deleteById(CharacterId characterId) {
    if (characterId == null) {
      throw new IllegalArgumentException("CharacterId cannot be null");
    }
    jpaRepo.deleteById(characterId.value());
  }

  @Override
  @Transactional(readOnly = true)
  public boolean existsById(CharacterId characterId) {
    if (characterId == null) {
      throw new IllegalArgumentException("CharacterId cannot be null");
    }
    return jpaRepo.existsById(characterId.value());
  }
}
