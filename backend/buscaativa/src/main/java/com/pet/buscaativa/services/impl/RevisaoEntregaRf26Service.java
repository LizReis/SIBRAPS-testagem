package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.services.exceptions.ConflictException;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import lombok.RequiredArgsConstructor;

/** Encerra uma reserva ambígua para conciliação humana. A chave V16 continua ocupada. */
@Service
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
public class RevisaoEntregaRf26Service {
    private final JdbcTemplate jdbc;
    private final EstruturaTransicaoLocal estrutura;
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final AgendaAssistencialLocks locks;
    private final Clock clock;

    public record Incidente(LocalDate dia, UUID destinatario, String estado, boolean revisado) {}

    @Transactional(readOnly = true)
    public java.util.List<Incidente> listar(LocalDate dia) {
        var ator = atores.autorizar(true);
        estrutura.exigir();
        if (dia == null) throw new ValidationException("Informe o dia da reserva.");
        return jdbc.query("""
                select e.dia,e.destinatario,e.estado,r.dia is not null as revisada from tb_entrega_rf26 e
                left join tb_revisao_entrega_rf26 r on r.dia=e.dia and r.destinatario=e.destinatario and r.unidade=e.unidade
                where e.unidade=? and e.dia=? and e.estado in ('RESERVADA','INCERTA') order by e.destinatario
                """, (rs, linha) -> new Incidente(rs.getObject(1, LocalDate.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getBoolean(4)), ator.unidade(), dia);
    }

    @Transactional
    public void encerrarSemReenvio(LocalDate dia, UUID destinatario, String justificativa) {
        var ator = atores.autorizar(true);
        estrutura.exigir();
        var motivo = justificativa == null ? "" : justificativa.strip();
        if (dia == null || destinatario == null || motivo.length() < 10 || motivo.length() > 2000)
            throw new ValidationException("Informe a reserva e justifique o encerramento.");
        var estado = jdbc.query("""
                select estado from tb_entrega_rf26 where dia=? and destinatario=? and unidade=? for update
                """, (rs, linha) -> rs.getString(1), dia, destinatario, ator.unidade())
                .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Reserva técnica não localizada."));
        atores.validar(ator, null, Map.of(ator.ator(), locks.travarUsuario(ator.ator())));
        if (!"RESERVADA".equals(estado) && !"INCERTA".equals(estado))
            throw new ConflictException("A reserva já tem resultado conclusivo.");
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tb_revisao_entrega_rf26 where dia=? and destinatario=? and unidade=?)
                """, Boolean.class, dia, destinatario, ator.unidade())))
            throw new ConflictException("A reserva já foi revisada.");
        jdbc.update("""
                insert into tb_revisao_entrega_rf26(dia,destinatario,unidade,administrador,revisada_em,estado_anterior,justificativa)
                values (?,?,?,?,?,?,?)
                """, dia, destinatario, ator.unidade(), ator.ator(), OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                estado, motivo);
        if ("RESERVADA".equals(estado)) jdbc.update("""
                update tb_entrega_rf26 set estado='INCERTA', finalizada_em=greatest(current_timestamp,reservada_em)
                where dia=? and destinatario=? and unidade=?
                """, dia, destinatario, ator.unidade());
    }
}
