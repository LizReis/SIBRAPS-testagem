import { provideHttpClient } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { EMPTY, of } from 'rxjs';
import { GrupoTerapeuticoService } from '../../services/grupo-terapeutico-service';
import { ProfissionalService } from '../../services/profissional/profissional-service';

import { AgendarGrupoTerapeutico } from './agendar-grupo-terapeutico';

describe('AgendarGrupoTerapeutico', () => {
  let component: AgendarGrupoTerapeutico;
  let fixture: ComponentFixture<AgendarGrupoTerapeutico>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AgendarGrupoTerapeutico],
      providers: [provideHttpClient(), provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(AgendarGrupoTerapeutico);
    component = fixture.componentInstance;
  });

  it('envia formulário de sessão única preenchido pelos controles nativos', async () => {
    const profissionais = TestBed.inject(ProfissionalService);
    vi.spyOn(profissionais, 'listarParaSelecao').mockReturnValue(of([{
      idPublico: 'prof-sintetico', nome: 'Profissional sintético', email: 'prof@example.invalid',
      senha: null, tipoUsuario: 'PROFISSIONAL', unidadeAtuacao: 'USF'
    }]));
    const criar = vi.spyOn(TestBed.inject(GrupoTerapeuticoService), 'criar').mockReturnValue(EMPTY);
    fixture.detectChanges();
    await fixture.whenStable();
    const campos = {
      tema: 'Grupo sintético', coordenador: 'prof-sintetico',
      data: component.dataMinima, horario: '19:30', recorrencia: 'UNICA'
    };
    for (const [id, valor] of Object.entries(campos)) {
      const el = fixture.nativeElement.querySelector('#' + id) as HTMLInputElement | HTMLSelectElement;
      el.value = valor;
      el.dispatchEvent(new Event(el.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true }));
    }
    await fixture.whenStable();
    expect(component.form.valid).toBe(true);
    const botao = fixture.nativeElement.querySelector('form button[type="submit"]') as HTMLButtonElement;
    expect(botao.textContent?.trim()).toBe('Agendar grupo');
    expect(botao.querySelector('i')?.getAttribute('aria-hidden')).toBe('true');
    expect(botao.disabled).toBe(false);
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    expect(criar).toHaveBeenCalledExactlyOnceWith({
      tema: 'Grupo sintético', coordenadorId: 'prof-sintetico', recorrencia: 'UNICA',
      dataPrimeiraSessao: component.dataMinima, dataFimRecorrencia: null,
      horario: '19:30', participantesIds: []
    });
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
