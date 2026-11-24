import { describe, it, expect } from 'vitest';
import { rotuloRiscoLocal, rotuloFaltasLocal, rotuloUltimaPresencaLocal,
  segmentosLocais, valoresEvolucaoLocal, rotuloRevisaoLocal } from './contrato-clinico-local';

describe('Contrato clínico local experimental', () => {
  it('não transforma risco ou falta desconhecidos em verde ou zero', () => {
    expect(rotuloRiscoLocal(null)).toBe('Não confirmado');
    expect(rotuloRiscoLocal('VERDE')).toBe('Regular');
    expect(rotuloFaltasLocal(null)).toBe('Não confirmado');
    expect(rotuloFaltasLocal(0)).toBe('0');
  });
  it('distingue ausência comprovada de presença desconhecida', () => {
    expect(rotuloUltimaPresencaLocal({dataUltimaPresenca:null,ultimaPresencaConclusiva:false})).toBe('Não confirmada');
    expect(rotuloUltimaPresencaLocal({dataUltimaPresenca:null,ultimaPresencaConclusiva:true})).toBe('Nenhuma presença comprovada');
  });
  it('mantém uma quarta categoria sem incorporar desconhecidos em verde', () => {
    const s = segmentosLocais({verdes:2,amarelos:1,vermelhos:1,naoConfirmados:1,
      percentualVerdes:40,percentualAmarelos:20,percentualVermelhos:20,percentualNaoConfirmados:20});
    expect(s.map(v=>v.quantidade)).toEqual([2,1,1,1]);
    expect(s[3].classe).toBe('unknown');
  });
  it('não interpreta aprovação como correção aplicada', () => {
    expect(rotuloRevisaoLocal('APROVADA')).toContain('sem aplicação automática');
    expect(rotuloRevisaoLocal('PENDENTE')).toBe('Pendente de revisão');
  });
  it('mantém lacunas históricas mesmo se receber contagens antigas', () => {
    expect(valoresEvolucaoLocal([
      {mes:'2026-10',rotulo:'Out',verdes:9,amarelos:0,vermelhos:0,disponivel:false},
      {mes:'2026-11',rotulo:'Nov',verdes:0,amarelos:0,vermelhos:0,disponivel:true}
    ],'verdes')).toEqual([null,0]);
  });
});
