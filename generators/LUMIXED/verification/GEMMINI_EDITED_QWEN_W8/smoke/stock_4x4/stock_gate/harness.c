#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <sys/types.h>
unsigned long gemmini_setup_cycles;
#include "gemmini_edited.h"
extern const uint32_t inputs[R*K];
extern const uint8_t weights[K*N];
_Alignas(64) static uint32_t guarded[R*N+32];
static inline unsigned long cycle(void){unsigned long t;asm volatile("rdcycle %0":"=r"(t));return t;}
static inline unsigned long ctr(unsigned arg){unsigned long value;ROCC_INSTRUCTION_R_R_R(XCUSTOM_ACC,value,arg,0,126);return value;}
extern ssize_t _write(int,const void*,size_t);
int main(void){
 for(unsigned i=0;i<R*N+32;i++)guarded[i]=0x0badc0de;
 uint32_t *out=guarded+16;
 gemmini_fpint_flush();asm volatile("fence":::"memory");
 // Seven mutually exclusive controller busy combinations, plus actual EX active.
 for(unsigned i=0;i<8;i++)ctr((i<<4)|8|((i<7?i+1:24)<<12));
 ctr(1);unsigned long begin=cycle();
 gemmini_fpint_matmul(R,K,N,inputs,weights,out);
 unsigned long end=cycle();ctr(4);
 unsigned long c[8];for(unsigned i=0;i<8;i++)c[i]=ctr(i<<4);
 unsigned long active=0;for(unsigned i=0;i<7;i++)active+=c[i];
 int guards=1;for(unsigned i=0;i<16;i++)if(guarded[i]!=0x0badc0de || guarded[R*N+16+i]!=0x0badc0de)guards=0;
 printf("GEMMINI_GUARDS %s\n",guards?"PASS":"FAIL");
 printf("GEMMINI_CYCLES invocation=%lu setup=%lu controller_active=%lu ld_only=%lu st_only=%lu ex_only=%lu ld_st=%lu ld_ex=%lu st_ex=%lu ld_st_ex=%lu execute_active=%lu\n",end-begin,gemmini_setup_cycles,active,c[0],c[1],c[2],c[3],c[4],c[5],c[6],c[7]);
 printf("GEMMINI_BINARY %u\n",R*N*4);fflush(stdout);
 const uint8_t *p=(const uint8_t*)out;size_t left=R*N*4;
 while(left){ssize_t n=_write(1,p,left);if(n<=0||(size_t)n>left)return 4;left-=n;p+=n;}
 printf("\nGEMMINI_END\n");return guards?0:5;
}
